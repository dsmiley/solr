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
package org.apache.solr.bench;

/**
 * Selects the Solr infrastructure backend used by a benchmark. The default for each benchmark is
 * set by the {@code defaultBackend} argument of {@link SolrBenchState#start}; it can be overridden
 * at the CLI with {@code -jvmArgs -Dsolr.bench.backend=minicluster|embedded|remote}.
 */
public enum SolrBenchBackendType {
  /** Embedded {@code MiniSolrCloudCluster} — full SolrCloud with ZooKeeper, in-process. */
  MINICLUSTER, // nocommit
  /** In-process {@code EmbeddedSolrServer} — no network or ZooKeeper overhead. */
  EMBEDDED,
  /** Pre-existing remote Solr instance (see {@code solr.bench.url}). */
  REMOTE
}
