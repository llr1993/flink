/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.plan.rules.physical.batch

import org.apache.flink.table.api.OperatorType
import org.apache.flink.table.calcite.FlinkContext
import org.apache.flink.table.plan.nodes.logical.FlinkLogicalJoin
import org.apache.flink.table.plan.nodes.physical.batch.BatchExecNestedLoopJoin

import org.apache.calcite.plan.RelOptRule.{any, operand}
import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Join, JoinRelType, SemiJoin}

/**
  * Rule that converts [[FlinkLogicalJoin]] to [[BatchExecNestedLoopJoin]]
  * if NestedLoopJoin is enabled.
  */
class BatchExecNestedLoopJoinRule(joinClass: Class[_ <: Join])
  extends RelOptRule(
    operand(joinClass,
      operand(classOf[RelNode], any)),
    s"BatchExecNestedLoopJoinRule_${joinClass.getSimpleName}")
  with BatchExecJoinRuleBase
  with BatchExecNestedLoopJoinRuleBase {

  override def matches(call: RelOptRuleCall): Boolean = {
    val tableConfig = call.getPlanner.getContext.asInstanceOf[FlinkContext].getTableConfig
    tableConfig.isOperatorEnabled(OperatorType.NestedLoopJoin)
  }

  override def onMatch(call: RelOptRuleCall): Unit = {
    val join: Join = call.rel(0)
    val left = join.getLeft
    val right = join.getRight
    val leftIsBuild = isLeftBuild(join, left, right)
    val newJoin = createNestedLoopJoin(join, left, right, leftIsBuild, singleRowJoin = false)
    call.transformTo(newJoin)
  }

  private def isLeftBuild(join: Join, left: RelNode, right: RelNode): Boolean = {
    if (join.isInstanceOf[SemiJoin]) {
      return false
    }
    join.getJoinType match {
      case JoinRelType.LEFT => false
      case JoinRelType.RIGHT => true
      case JoinRelType.INNER | JoinRelType.FULL =>
        val leftSize = binaryRowRelNodeSize(left)
        val rightSize = binaryRowRelNodeSize(right)
        // use left as build size if leftSize or rightSize is unknown.
        if (leftSize == null || rightSize == null) {
          true
        } else {
          leftSize <= rightSize
        }
    }
  }
}

object BatchExecNestedLoopJoinRule {
  val INSTANCE: RelOptRule = new BatchExecNestedLoopJoinRule(classOf[FlinkLogicalJoin])
}
