/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.solr.mcf;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.queries.*;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.net.*;

/**
* SearchComponent plugin for ManifoldCF-specific document-level access control.
* Configuration is under the SolrACLSecurity name.
*/
public class ManifoldCFSecurityFilter extends SearchComponent
{
  /** The component name */
  static final public String COMPONENT_NAME = "mcf";
  /** The parameter that is supposed to contain the authenticated user name, possibly including the domain */
  static final public String AUTHENTICATED_USER_NAME = "AuthenticatedUserName";
  /** This parameter is an array of strings, which contain the tokens to use if there is no authenticated user name.
   * It's meant to work with mod_authz_annotate,
   * running under Apache */
  static final public String USER_TOKENS = "UserTokens";
  
  /** The queries that we will not attempt to interfere with */
  static final private String[] globalAllowed = { "solrpingquery" };
  
  /** A logger we can use */
  private static final Logger LOG = LoggerFactory.getLogger(ManifoldCFSecurityFilter.class);

  // Member variables
  String authorityBaseURL = null;
  String fieldAllowDocument = null;
  String fieldDenyDocument = null;
  String fieldAllowShare = null;
  String fieldDenyShare = null;
  int socketTimeOut;
  
  public ManifoldCFSecurityFilter()
  {
    super();
  }

  @Override
  public void init(NamedList args)
  {
    super.init(args);
    authorityBaseURL = (String)args.get("AuthorityServiceBaseURL");
    if (authorityBaseURL == null)
      authorityBaseURL = "http://localhost:8345/mcf-authority-service";
    Integer timeOut = (Integer)args.get("SocketTimeOut");
    socketTimeOut = timeOut == null ? 300000 : timeOut;
    String allowAttributePrefix = (String)args.get("AllowAttributePrefix");
    String denyAttributePrefix = (String)args.get("DenyAttributePrefix");
    if (allowAttributePrefix == null)
      allowAttributePrefix = "allow_token_";
    if (denyAttributePrefix == null)
      denyAttributePrefix = "deny_token_";
    fieldAllowDocument = allowAttributePrefix+"document";
    fieldDenyDocument = denyAttributePrefix+"document";
    fieldAllowShare = allowAttributePrefix+"share";
    fieldDenyShare = denyAttributePrefix+"share";
  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException
  {
    SolrParams params = rb.req.getParams();
    if (!params.getBool(COMPONENT_NAME, true) || params.getBool(ShardParams.IS_SHARD, false))
      return;

    // Log that we got here
    //LOG.info("prepare() entry params:\n" + params + "\ncontext: " + rb.req.getContext());
		
    String qry = (String)params.get(CommonParams.Q);
    if (qry != null)
    {
      //Check global allowed searches
      for (String ga : globalAllowed)
      {
        if (qry.equalsIgnoreCase(ga.trim()))
          // Allow this query through unchanged
          return;
      }
    }

    List<String> userAccessTokens;
    
    // Get the authenticated user name from the parameters
    String authenticatedUserName = params.get(AUTHENTICATED_USER_NAME);
    
    // If this parameter is empty or does not exist, we have to presume this is a guest, and treat them accordingly
    if (authenticatedUserName == null || authenticatedUserName.length() == 0)
    {
      // No authenticated user name.
      // mod_authz_annotate may be in use upstream, so look for tokens from it.
      userAccessTokens = new ArrayList<String>();
      String[] passedTokens = params.getParams(USER_TOKENS);
      if (passedTokens == null)
      {
        // Only return 'public' documents (those with no security tokens at all)
        LOG.info("Default no-user response (open documents only)");
      }
      else
      {
        // Only return 'public' documents (those with no security tokens at all)
        LOG.info("Group tokens received from caller");
        for (String passedToken : passedTokens)
        {
          userAccessTokens.add(passedToken);
        }
      }
    }
    else
    {
      LOG.info("Trying to match docs for user '"+authenticatedUserName+"'");
      // Valid authenticated user name.  Look up access tokens for the user.
      // Check the configuration arguments for validity
      if (authorityBaseURL == null)
      {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error initializing ManifoldCFSecurityFilter component: 'AuthorityServiceBaseURL' init parameter required");
      }
      userAccessTokens = getAccessTokens(authenticatedUserName);
    }

    BooleanFilter bf = new BooleanFilter();
    
    if (userAccessTokens.size() == 0)
    {
      // Only open documents can be included.
      // That query is:
      // (fieldAllowShare is empty AND fieldDenyShare is empty AND fieldAllowDocument is empty AND fieldDenyDocument is empty)
      // We're trying to map to:  -(fieldAllowShare:*) , which should be pretty efficient in Solr because it is negated.  If this turns out not to be so, then we should
      // have the SolrConnector inject a special token into these fields when they otherwise would be empty, and we can trivially match on that token.
      bf.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(fieldAllowShare,"*"))),BooleanClause.Occur.MUST_NOT));
      bf.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(fieldDenyShare,"*"))),BooleanClause.Occur.MUST_NOT));
      bf.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(fieldAllowDocument,"*"))),BooleanClause.Occur.MUST_NOT));
      bf.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(fieldDenyDocument,"*"))),BooleanClause.Occur.MUST_NOT));
    }
    else
    {
      // Extend the query appropriately for each user access token.
      bf.add(new FilterClause(calculateCompleteSubfilter(fieldAllowShare,fieldDenyShare,userAccessTokens),BooleanClause.Occur.MUST));
      bf.add(new FilterClause(calculateCompleteSubfilter(fieldAllowDocument,fieldDenyDocument,userAccessTokens),BooleanClause.Occur.MUST));
    }

    // Concatenate with the user's original query.
    //FilteredQuery query = new FilteredQuery(rb.getQuery(),bf);
    //rb.setQuery(query);
    List<Query> list = rb.getFilters();
    if (list == null)
    {
      list = new ArrayList<Query>();
      rb.setFilters(list);
    }
    list.add(new ConstantScoreQuery(bf));
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException
  {
    //LOG.info("process() called");
  }

  /** Calculate a complete subclause, representing something like:
  * ((fieldAllowShare is empty AND fieldDenyShare is empty) OR fieldAllowShare HAS token1 OR fieldAllowShare HAS token2 ...)
  *     AND fieldDenyShare DOESN'T_HAVE token1 AND fieldDenyShare DOESN'T_HAVE token2 ...
  */
  protected Filter calculateCompleteSubfilter(String allowField, String denyField, List<String> userAccessTokens)
  {
    BooleanFilter bf = new BooleanFilter();
    
    // Add a clause for each token.  This will be added directly to the main filter (as a deny test), as well as to an OR's subclause (as an allow test).
    BooleanFilter orFilter = new BooleanFilter();
    // Add the empty-acl case
    BooleanFilter subUnprotectedClause = new BooleanFilter();
    subUnprotectedClause.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(allowField,"*"))),BooleanClause.Occur.MUST_NOT));
    subUnprotectedClause.add(new FilterClause(new QueryWrapperFilter(new WildcardQuery(new Term(denyField,"*"))),BooleanClause.Occur.MUST_NOT));
    orFilter.add(new FilterClause(subUnprotectedClause,BooleanClause.Occur.SHOULD));
    for (String accessToken : userAccessTokens)
    {
      TermsFilter tf = new TermsFilter();
      tf.addTerm(new Term(allowField,accessToken));
      orFilter.add(new FilterClause(tf,BooleanClause.Occur.SHOULD));
      tf = new TermsFilter();
      tf.addTerm(new Term(denyField,accessToken));
      bf.add(new FilterClause(tf,BooleanClause.Occur.MUST_NOT));
    }
    bf.add(new FilterClause(orFilter,BooleanClause.Occur.MUST));
    return bf;
  }
  
  //---------------------------------------------------------------------------------
  // SolrInfoMBean
  //---------------------------------------------------------------------------------
  @Override
  public String getDescription()
  {
    return "ManifoldCF Solr security enforcement plugin";
  }

  @Override
  public String getVersion()
  {
    return "$Revision$";
  }

  @Override
  public String getSourceId()
  {
    return "$Id$";
  }

  @Override
  public String getSource()
  {
    return "$URL$";
  }
	
  // Protected methods
  
  /** Get access tokens given a username */
  protected List<String> getAccessTokens(String authenticatedUserName)
    throws IOException
  {
    // We can make this more complicated later, with support for https etc., but this is enough to demonstrate how it all should work.
    HttpClient client = new HttpClient();
    String theURL = authorityBaseURL + "/UserACLs?username="+URLEncoder.encode(authenticatedUserName,"utf-8");
      
    GetMethod method = new GetMethod(theURL);
    try
    {
      method.getParams().setParameter("http.socket.timeout", socketTimeOut);
      method.setFollowRedirects(true);
      int rval = client.executeMethod(method);
      if (rval != 200)
      {
        String response = method.getResponseBodyAsString();
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,"Couldn't fetch user's access tokens from ManifoldCF authority service: "+Integer.toString(rval)+"; "+response);
      }
      InputStream is = method.getResponseBodyAsStream();
      try
      {
        Reader r = new InputStreamReader(is,"utf-8");
        try
        {
          BufferedReader br = new BufferedReader(r);
          try
          {
            // Read the tokens, one line at a time.  If any authorities are down, we have no current way to note that, but someday we will.
            List<String> tokenList = new ArrayList<String>();
            while (true)
            {
              String line = br.readLine();
              if (line == null)
                break;
              if (line.startsWith("TOKEN:"))
              {
                tokenList.add(line.substring("TOKEN:".length()));
              }
              else
              {
                // It probably says something about the state of the authority(s) involved, so log it
                LOG.info("For user '"+authenticatedUserName+"', saw authority response "+line);
              }
            }
            return tokenList;
          }
          finally
          {
            br.close();
          }
        }
        finally
        {
          r.close();
        }
      }
      finally
      {
        is.close();
      }
    }
    finally
    {
      method.releaseConnection();
    }
  }
  
}