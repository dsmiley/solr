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

// TODO: This test class needs to be rewritten to use Jetty HttpClient instead of Apache HttpClient
// The original implementation was heavily dependent on Apache HttpClient's connection pooling and 
// management APIs (CloseableHttpClient, PoolingHttpClientConnectionManager, etc.) which don't 
// have direct equivalents in Jetty HttpClient. The test validated connection reuse behavior that
// works differently in Jetty HttpClient's architecture. This should be reimplemented to test
// connection reuse using Jetty HttpClient APIs or the test functionality should be covered
// through other means.
//
// Original test functionality:
// - Tested that multiple HTTP requests to Solr reuse the same underlying connection
// - Verified connection metrics to ensure proper connection pooling
// - Tested with different SolrClient implementations (HttpSolrClient, ConcurrentUpdateSolrClient, CloudSolrClient)
//
// For Jetty HttpClient equivalent, consider:
// - Using Jetty's ConnectionPool interface for connection management testing
// - Testing connection reuse through Jetty's HttpDestination metrics
// - Validating that Http2SolrClient properly shares connections when configured to do so

import org.apache.solr.SolrTestCaseJ4.SuppressSSL;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.junit.Ignore;

@SuppressSSL
@Ignore("TODO: Rewrite this test to use Jetty HttpClient instead of Apache HttpClient - see class-level comment")
public class ConnectionReuseTest extends SolrCloudTestCase {
  // Test implementation removed - needs rewrite for Jetty HttpClient
}
