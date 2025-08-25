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

/**
 * Index management and configuration classes for Solr's interaction with Lucene indexes.
 *
 * <p>This package provides factories and utilities for managing Lucene index behavior within Solr,
 * including:
 *
 * <ul>
 *   <li>{@link org.apache.solr.index.MergePolicyFactory} and its implementations for configuring
 *       how index segments are merged
 *   <li>{@link org.apache.solr.index.SlowCompositeReaderWrapper} for wrapping composite readers as
 *       leaf readers when needed
 *   <li>Various merge policy factory implementations like {@link
 *       org.apache.solr.index.TieredMergePolicyFactory} and {@link
 *       org.apache.solr.index.LogByteSizeMergePolicyFactory}
 * </ul>
 *
 * <p>These classes provide Solr-specific wrappers and extensions around Lucene's core indexing
 * functionality, allowing for configuration and customization of index behavior through Solr's
 * configuration system.
 */
package org.apache.solr.index;
