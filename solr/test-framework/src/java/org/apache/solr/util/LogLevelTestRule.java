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

package org.apache.solr.util;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.solr.common.util.SuppressForbidden;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit {@link TestRule} that sets (and resets) the Log4j2 log level based on the {@code
 * LogLevel} annotation.
 */
public class LogLevelTestRule implements TestRule {

  private static final String globalLogLevel = System.getProperty("logLevel");

  @Override
  public Statement apply(Statement base, Description description) {
    // loop over the annotations to find LogLevel
    final Optional<Annotation> annotationOpt =
        description.getAnnotations().stream()
            .filter(a -> a.annotationType().equals(LogLevel.class))
            .findAny();
    final String logLevelValue;
    if (annotationOpt.isPresent()) {
      logLevelValue = ((LogLevel) annotationOpt.get()).value();
    } else if (globalLogLevel != null) {
      logLevelValue = globalLogLevel;
    } else {
      return base;
    }
    return new LogLevelStatement(base, logLevelValue);
  }

  static class LogLevelStatement extends Statement {
    private final Statement delegate;
    private final String logLevelValue;

    protected LogLevelStatement(Statement delegate, String logLevelValue) {
      this.delegate = delegate;
      this.logLevelValue = logLevelValue;
    }

    @SuppressForbidden(reason = "Using the Level class from log4j2 directly")
    @Override
    public void evaluate() throws Throwable {
      Map<String, Level> savedLogLevels = LogLevel.Configurer.setLevels(logLevelValue);
      try {
        delegate.evaluate();
      } finally {
        LogLevel.Configurer.restoreLogLevels(savedLogLevels);
      }
    }
  }
}
