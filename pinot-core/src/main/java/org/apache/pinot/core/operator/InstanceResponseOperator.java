/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.operator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.pinot.common.utils.DataTable.MetadataKey;
import org.apache.pinot.core.common.Operator;
import org.apache.pinot.core.operator.blocks.InstanceResponseBlock;
import org.apache.pinot.core.operator.blocks.IntermediateResultsBlock;
import org.apache.pinot.core.operator.combine.BaseCombineOperator;
import org.apache.pinot.core.query.request.context.ThreadTimer;
import org.apache.pinot.segment.spi.FetchContext;
import org.apache.pinot.segment.spi.IndexSegment;


public class InstanceResponseOperator extends BaseOperator<InstanceResponseBlock> {
  private static final String OPERATOR_NAME = "InstanceResponseOperator";
  private static final String EXPLAIN_NAME = "INSTANCE_RESPONSE";

  private final BaseCombineOperator _combineOperator;
  private final List<IndexSegment> _indexSegments;
  private final List<FetchContext> _fetchContexts;
  private final int _fetchContextSize;

  public InstanceResponseOperator(BaseCombineOperator combinedOperator, List<IndexSegment> indexSegments,
      List<FetchContext> fetchContexts) {
    _combineOperator = combinedOperator;
    _indexSegments = indexSegments;
    _fetchContexts = fetchContexts;
    _fetchContextSize = fetchContexts.size();
  }

  /*
   * Derive systemActivitiesCpuTimeNs from totalWallClockTimeNs, multipleThreadCpuTimeNs, mainThreadCpuTimeNs,
   * and numServerThreads.
   *
   * For example, let's divide query processing into 4 phases:
   * - phase 1: single thread (main thread) preparing. Time used: T1
   * - phase 2: N threads processing segments in parallel, each thread use time T2
   * - phase 3: system activities (GC/OS paging). Time used: T3
   * - phase 4: single thread (main thread) merging intermediate results blocks. Time used: T4
   *
   * Then we have following equations:
   * - mainThreadCpuTimeNs = T1 + T4
   * - multipleThreadCpuTimeNs = T2 * N
   * - totalWallClockTimeNs = T1 + T2 + T3 + T4 = mainThreadCpuTimeNs + T2 + T3
   * - systemActivitiesCpuTimeNs = T3 = totalWallClockTimeNs - mainThreadCpuTimeNs - T2
   */
  public static long calSystemActivitiesCpuTimeNs(long totalWallClockTimeNs, long multipleThreadCpuTimeNs,
      long mainThreadCpuTimeNs, int numServerThreads) {
    double perMultipleThreadCpuTimeNs = multipleThreadCpuTimeNs * 1.0 / numServerThreads;
    long systemActivitiesCpuTimeNs =
        Math.round(totalWallClockTimeNs - mainThreadCpuTimeNs - perMultipleThreadCpuTimeNs);
    // systemActivitiesCpuTimeNs should not be negative, this is just a defensive check
    return Math.max(systemActivitiesCpuTimeNs, 0);
  }

  @Override
  protected InstanceResponseBlock getNextBlock() {
    if (ThreadTimer.isThreadCpuTimeMeasurementEnabled()) {
      long startWallClockTimeNs = System.nanoTime();

      ThreadTimer mainThreadTimer = new ThreadTimer();
      IntermediateResultsBlock intermediateResultsBlock = getCombinedResults();
      InstanceResponseBlock instanceResponseBlock = new InstanceResponseBlock(intermediateResultsBlock);
      long mainThreadCpuTimeNs = mainThreadTimer.getThreadTimeNs();

      long totalWallClockTimeNs = System.nanoTime() - startWallClockTimeNs;
      /*
       * If/when the threadCpuTime based instrumentation is done for other parts of execution (planning, pruning etc),
       * we will have to change the wallClockTime computation accordingly. Right now everything under
       * InstanceResponseOperator is the one that is instrumented with threadCpuTime.
       */
      long multipleThreadCpuTimeNs = intermediateResultsBlock.getExecutionThreadCpuTimeNs();
      int numServerThreads = intermediateResultsBlock.getNumServerThreads();
      long systemActivitiesCpuTimeNs =
          calSystemActivitiesCpuTimeNs(totalWallClockTimeNs, multipleThreadCpuTimeNs, mainThreadCpuTimeNs,
              numServerThreads);

      long threadCpuTimeNs = mainThreadCpuTimeNs + multipleThreadCpuTimeNs;
      Map<String, String> responseMetaData = instanceResponseBlock.getInstanceResponseDataTable().getMetadata();
      responseMetaData.put(MetadataKey.THREAD_CPU_TIME_NS.getName(), String.valueOf(threadCpuTimeNs));
      responseMetaData
          .put(MetadataKey.SYSTEM_ACTIVITIES_CPU_TIME_NS.getName(), String.valueOf(systemActivitiesCpuTimeNs));

      return instanceResponseBlock;
    } else {
      return new InstanceResponseBlock(getCombinedResults());
    }
  }

  private IntermediateResultsBlock getCombinedResults() {
    try {
      prefetchAll();
      return _combineOperator.nextBlock();
    } finally {
      releaseAll();
    }
  }

  private void prefetchAll() {
    for (int i = 0; i < _fetchContextSize; i++) {
      _indexSegments.get(i).prefetch(_fetchContexts.get(i));
    }
  }

  private void releaseAll() {
    for (int i = 0; i < _fetchContextSize; i++) {
      _indexSegments.get(i).release(_fetchContexts.get(i));
    }
  }

  @Override
  public String getOperatorName() {
    return OPERATOR_NAME;
  }

  @Override
  public String toExplainString() {
    return EXPLAIN_NAME;
  }

  @Override
  public List<Operator> getChildOperators() {
    return Collections.singletonList(_combineOperator);
  }
}
