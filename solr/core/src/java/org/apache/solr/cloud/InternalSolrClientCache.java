/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.jetty.CloudJettySolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.EnvUtils;
import org.apache.solr.common.util.URLUtil;

/**
 * A restricted {@link SolrClientCache} for internal Solr use. See {@link
 * #ALLOW_EXTERNAL_CLUSTERS_PROPERTY} to open it up.
 */
public class InternalSolrClientCache extends SolrClientCache {

  static {
    assert INTERNAL_IMPL_CLASS.equals(InternalSolrClientCache.class.getName())
        : "Update SolrClientCache.INTERNAL_IMPL_CLASS to match the renamed class";
  }

  public static final String ALLOW_EXTERNAL_CLUSTERS_PROPERTY = "solr.cloud.external.enabled";

  private final CloudSolrClient.CloudSolrClientConnection defaultConnection;

  public InternalSolrClientCache(
      HttpJettySolrClient httpSolrClient,
      CloudSolrClient.CloudSolrClientConnection solrConnection) {
    super(); // not passing httpSolrClient down ...
    this.defaultConnection = solrConnection;
    // ... create one internal CloudSolrClient that is a bit special.
    var httpBuilder =
        new HttpJettySolrClient.Builder()
            .withIdleTimeout(minSocketTimeout, TimeUnit.MILLISECONDS)
            .withHttpClient(httpSolrClient);
    cloudSolClients.put(
        solrConnection,
        new CloudJettySolrClient.Builder(solrConnection)
            .canUseZkACLs(true)
            .withHttpClientBuilder(httpBuilder)
            .build());
  }

  @Override
  public synchronized CloudSolrClient getCloudSolrClient(
      CloudSolrClient.CloudSolrClientConnection solrConnection) {
    if (solrConnection == null) {
      solrConnection = defaultConnection;
    }
    CloudSolrClient client = cloudSolClients.get(solrConnection);
    if (client != null) {
      return client;
    }
    if (EnvUtils.getPropertyAsBool(ALLOW_EXTERNAL_CLUSTERS_PROPERTY, false)) {
      return super.getCloudSolrClient(solrConnection);
    }
    throw new SolrException(
        SolrException.ErrorCode.FORBIDDEN,
        "External solr cluster is not allowed: "
            + solrConnection
            + ". To allow external clusters set -Dsolr.enable-external-clusters=true "
            + "(WARNING: this may enable SSRF attacks)");
  }

  @Override
  protected HttpSolrClient.BuilderBase<?, ?> newHttpSolrClientBuilder(String url) {
    if (url != null) {
      // override to attempt to use the jetty client inside a matching CloudSolrClient.
      String baseUrl = URLUtil.isBaseUrl(url) ? url : URLUtil.extractBaseUrl(url);
      try {
        String nodeName = URLUtil.getNodeNameForBaseUrl(baseUrl);
        for (CloudSolrClient cloudSolrClient : cloudSolClients.values()) {
          if (cloudSolrClient.getClusterStateProvider().getLiveNodes().contains(nodeName)) {
            final var builder = new HttpJettySolrClient.Builder(baseUrl);
            if (!URLUtil.isBaseUrl(url)) {
              builder.withDefaultCollection(URLUtil.extractCoreFromCoreUrl(url));
            }
            builder.withHttpClient(
                (HttpJettySolrClient) ((CloudHttp2SolrClient) cloudSolrClient).getHttpClient());
            return builder;
          }
        }
      } catch (MalformedURLException | URISyntaxException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
      }
    }
    return super.newHttpSolrClientBuilder(url);
  }
}
