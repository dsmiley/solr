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
package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.util.LogListener;
import org.junit.BeforeClass;
import org.junit.Test;

public final class CloudSolrClientRoutingTest extends SolrCloudTestCase {

  /**
   * Create a cluster with 2 shards (each with a single replica) on a single node (localhost). Set
   * "id" as the router field.
   */
  @BeforeClass
  public static void setupCluster() throws Exception {
    //    configset("cloud-minimal");
    configureCluster(1)
        .addConfig("conf", getFile("solrj/solr/configsets/streaming/conf"))
        .configure();

    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection("test-collection", 2, 1);
    create.setRouterField("id");
    create.setReplicationFactor(1);
    create.process(cluster.getSolrClient());

    cluster.waitForActiveCollection("test-collection", 2, 2);
  }

  @Test
  public void routeParamHandling() throws IOException, SolrServerException {

    String collection = "test-collection";

    SolrClient client = cluster.getSolrClient();

    // Index a bunch of records with different ids
    int numDocuments = random().nextInt(500) + 500;
    List<SolrInputDocument> documents = new ArrayList<>(numDocuments);
    for (int i = 0; i < 1_000; i++) {
      String docId = Integer.toString(random().nextInt(Integer.MAX_VALUE));
      SolrInputDocument document = new SolrInputDocument("id", docId);
      documents.add(document);
    }
    client.add(collection, documents);
    client.commit(collection, true, true);

    int numForwardedWithRoute = 0;
    int numForwardedWithoutRoute = 0;

    for (int i = 0; i < 100; i++) {
      String docId = documents.get(random().nextInt(numDocuments)).getFieldValue("id").toString();

      SolrQuery queryWithoutRoute =
          new SolrQuery(
              UpdateParams.COLLECTION,
              collection,
              "q",
              "id:" + docId,
              CommonParams.DEBUG_QUERY,
              "on");

      // Query with _route_=<docId>.
      SolrQuery queryWithRoute = queryWithoutRoute.getCopy().setParam(ShardParams._ROUTE_, docId);

      // TRACK is present in the response only when the request is processed as a distributed
      // request (i.e. Solr uses ShardHandler to forward the request to one or more replicas
      // internally), so ideally it should never appear in the response since we're specifying
      // the _route_ param, telling Solr exactly which replica the doc is in.
      // However, the TRACK value appears randomly in the response because routing is done only to
      // the node level, and once the request reaches the node, the replica is chosen randomly
      // at the mercy of org.apache.solr.servlet.HttpSolrCall.randomlyGetSolrCore, and if this
      // randomly selected replica is not the one the doc id lives in, the request is forwarded
      // from there.
      // The decision whether the request is forwarded is made at
      // org.apache.solr.handler.component.HttpShardHandler.canShortCircuit()

      boolean forwardedWithoutRoute =
          ((NamedList<?>)
                      client
                          .query(collection, queryWithoutRoute)
                          .getResponse()
                          .get(CommonParams.DEBUG))
                  .get(CommonParams.TRACK)
              != null;
      if (forwardedWithoutRoute) {
        numForwardedWithoutRoute++;
      }

      boolean forwardedWithRoute =
          ((NamedList<?>)
                      client
                          .query(collection, queryWithRoute)
                          .getResponse()
                          .get(CommonParams.DEBUG))
                  .get(CommonParams.TRACK)
              != null;
      if (forwardedWithRoute) {
        numForwardedWithRoute++;
      }
    }

    assertEquals(0, numForwardedWithRoute);
    assertEquals(100, numForwardedWithoutRoute);
  }

  /**
   * Tests that when the client's local cluster state is stale (it doesn't know about a collection
   * that exists on the server), the client routes to an arbitrary live node instead of failing
   * locally with "Collection not found". The server handles the request normally.
   */
  @Test
  public void testRouteToArbitraryNodeWhenCollectionUnknown()
      throws IOException, SolrServerException {
    ClusterStateProvider realCsp = cluster.getSolrClient().getClusterStateProvider();
    // Create a "stale" CSP: knows about live nodes but returns null for all collection state,
    // simulating a client whose local cluster state view hasn't caught up yet.
    ClusterStateProvider staleCsp =
        new DelegatingClusterStateProvider(realCsp) {
          @Override
          public ClusterState.CollectionRef getState(String collection) {
            return null; // simulate stale state: collection is unknown locally
          }

          @Override
          public void close() {
            // do not close the delegate; it is owned by the cluster
          }
        };

    try (LogListener warnLog =
            LogListener.warn(CloudSolrClient.class).substring("routing to an arbitrary live node");
        CloudSolrClient client = new CloudSolrClient.Builder(staleCsp).build()) {
      // The query should succeed even though the local cluster state doesn't know about the
      // collection. The client should route to an arbitrary live node and let the server handle it.
      var response = client.query("test-collection", new SolrQuery("*:*"));
      assertNotNull(response);
      // Verify that the client logged a warning about routing to an unknown collection.
      assertTrue(warnLog.pollMessage().contains("test-collection"));
    }
  }
}
