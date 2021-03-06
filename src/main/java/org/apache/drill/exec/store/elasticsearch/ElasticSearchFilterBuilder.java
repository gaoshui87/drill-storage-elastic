/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.elasticsearch;

import java.io.IOException;
import java.util.List;

import org.apache.drill.common.expression.BooleanOperator;
import org.apache.drill.common.expression.FunctionCall;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.expression.visitors.AbstractExprVisitor;
import org.apache.drill.exec.store.mongo.MongoCompareFunctionProcessor;
import org.apache.drill.exec.store.mongo.MongoScanSpec;
import org.apache.drill.exec.store.mongo.common.MongoCompareOp;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

public class ElasticSearchFilterBuilder extends
    AbstractExprVisitor<ElasticSearchScanSpec, Void, RuntimeException> implements
    ElasticSearchConstants {
  static final Logger logger = LoggerFactory
      .getLogger(ElasticSearchFilterBuilder.class);
  final ElasticSearchGroupScan groupScan;
  final LogicalExpression le;
	// 是不是所有的函数能转换的了
  private boolean allExpressionsConverted = true;

  public ElasticSearchFilterBuilder(ElasticSearchGroupScan groupScan,
      LogicalExpression conditionExp) {
    this.groupScan = groupScan;
    this.le = conditionExp;
  }

  public ElasticSearchScanSpec parseTree() {
	  // 以观察者的方式来修改查询条件
	  ElasticSearchScanSpec parsedSpec = le.accept(this, null);
    if (parsedSpec != null) {
    	// 并上这一个条件,因为下面还没有使用的到
      parsedSpec = mergeScanSpecs("booleanAnd", this.groupScan.getScanSpec(),
          parsedSpec);
    }
    return parsedSpec;
  }

  private ElasticSearchScanSpec mergeScanSpecs(String functionName,
		  ElasticSearchScanSpec leftScanSpec, ElasticSearchScanSpec rightScanSpec) {
    String newFilter = new String();

    switch (functionName) {
    case "booleanAnd":
      if (leftScanSpec.getFilters() != null
          && rightScanSpec.getFilters() != null) {
    	  // 两个条件并起来
        newFilter = ElasticSearchUtils.andFilterAtIndex(leftScanSpec.getFilters(),
            rightScanSpec.getFilters());
      } else if (leftScanSpec.getFilters() != null) {
        newFilter = leftScanSpec.getFilters();
      } else {
        newFilter = rightScanSpec.getFilters();
      }
      break;
    case "booleanOr":
      newFilter = ElasticSearchUtils.orFilterAtIndex(leftScanSpec.getFilters(),
          rightScanSpec.getFilters());
    }
    return new ElasticSearchScanSpec(groupScan.getScanSpec().getIndexName(), groupScan
            .getScanSpec().getTypeMappingName(), groupScan.getScanSpec().getPartitionDefinition(),newFilter);
  }

  public boolean isAllExpressionsConverted() {
    return allExpressionsConverted;
  }

  @Override
  public ElasticSearchScanSpec visitUnknown(LogicalExpression e, Void value)
      throws RuntimeException {
    allExpressionsConverted = false;
    return null;
  }

  @Override
  public ElasticSearchScanSpec visitBooleanOperator(BooleanOperator op, Void value) {
    List<LogicalExpression> args = op.args;
    ElasticSearchScanSpec nodeScanSpec = null;
    String functionName = op.getName();
    for (int i = 0; i < args.size(); ++i) {
      switch (functionName) {
      case "booleanAnd":
      case "booleanOr":
        if (nodeScanSpec == null) {
        	// 对当前的表达式进行处理
          nodeScanSpec = args.get(i).accept(this, null);
        } else {
        	ElasticSearchScanSpec scanSpec = args.get(i).accept(this, null);
          if (scanSpec != null) {
            nodeScanSpec = mergeScanSpecs(functionName, nodeScanSpec, scanSpec);
          } else {
        	  // 说明对这个表达式不知道怎么处理
            allExpressionsConverted = false;
          }
        }
        break;
      }
    }
    return nodeScanSpec;
  }

  @Override
  public ElasticSearchScanSpec visitFunctionCall(FunctionCall call, Void value)
      throws RuntimeException {
	  ElasticSearchScanSpec nodeScanSpec = null;
    String functionName = call.getName();
    ImmutableList<LogicalExpression> args = call.args;

    if (MongoCompareFunctionProcessor.isCompareFunction(functionName)) {
    	// 当是大小比较时,然后下面进行类型转换然后执行
      MongoCompareFunctionProcessor processor = MongoCompareFunctionProcessor
          .process(call);
      if (processor.isSuccess()) {
        try {
        	// 生成函数判断了
          nodeScanSpec = createMongoScanSpec(processor.getFunctionName(),
              processor.getPath(), processor.getValue());
        } catch (Exception e) {
          logger.error(" Failed to creare Filter ", e);
          // throw new RuntimeException(e.getMessage(), e);
        }
      }
    } else {
      switch (functionName) {
      case "booleanAnd":
      case "booleanOr":
    	  ElasticSearchScanSpec leftScanSpec = args.get(0).accept(this, null);
    	  ElasticSearchScanSpec rightScanSpec = args.get(1).accept(this, null);
        if (leftScanSpec != null && rightScanSpec != null) {
          nodeScanSpec = mergeScanSpecs(functionName, leftScanSpec,
              rightScanSpec);
        } else {
        	// 说明未不是所有的函数能转换的了
          allExpressionsConverted = false;
          if ("booleanAnd".equals(functionName)) {
            nodeScanSpec = leftScanSpec == null ? rightScanSpec : leftScanSpec;
          }
        }
        break;
      }
    }

    if (nodeScanSpec == null) {
      allExpressionsConverted = false;
    }

    return nodeScanSpec;
  }

  private ElasticSearchScanSpec createMongoScanSpec(String functionName,
      SchemaPath field, Object fieldValue ) throws ClassNotFoundException,
      IOException {
	  // 执行函数操作
    // extract the field name
    String fieldName = field.getAsUnescapedPath();
    boolean strictPushDown = true;
	
	String queryFilter  = translateFilter(  functionName ,  fieldName, fieldValue ,  strictPushDown);
 
      // 执行完成了
      return new ElasticSearchScanSpec(groupScan.getScanSpec().getIndexName(), groupScan
          .getScanSpec().getTypeMappingName(), groupScan.getScanSpec().getPartitionDefinition(),queryFilter);
 
  }
  
  private String translateFilter(String functionName ,String fieldName, Object fieldValue ,boolean strictPushDown){
	  String queryFilter = "";
	  
	   switch (functionName) {
	    case "equal":
	      
	      if (strictPushDown) queryFilter = String.format( "\"{\"term\":{\"%s\":%s}}\"" , fieldName ,fieldValue); 
	      else queryFilter = String.format( "\"{\"query\":{\"match\":{\"%s\":%s}}}\"" , fieldName ,fieldValue);  
	      
	      break;
	    case "not_equal":
	      
	      queryFilter = String.format( "\"{\"not\":{\"filter\":%s}}\"",translateFilter(  "equal" ,  fieldName,   fieldValue ,  strictPushDown));
	      break;
	    case "greater_than_or_equal_to":
	      queryFilter =  String.format("\"{\"range\":{\"%s\":{\"gte\" :%s\"}}}" ,fieldName , fieldValue) ;
	      break;
	    case "greater_than":
	    	 queryFilter =  String.format("\"{\"range\":{\"%s\":{\"gt\" :%s\"}}}" ,fieldName , fieldValue) ;
	      break;
	    case "less_than_or_equal_to":
	    	 queryFilter =  String.format("\"{\"range\":{\"%s\":{\"lte\" :%s\"}}}" ,fieldName , fieldValue) ;
	      break;
	    case "less_than":
	    	 queryFilter =  String.format("\"{\"range\":{\"%s\":{\"lt\" :%s\"}}}" ,fieldName , fieldValue) ;
	      break;
	    case "isnull":
	    case "isNull":
	    case "is null":
	    	  queryFilter =   String.format("\"{\"missing\":{\"field\":\"%s\"}}\"", fieldName);
	      break;
	    case "isnotnull":
	    case "isNotNull":
	    case "is not null":
	    	  queryFilter =   String.format("\"{\"exists\":{\"field\":\"%s\"}}\"", fieldName);
	      break;
	      default :
	    	  throw new UnsupportedOperationException(functionName);
	    }
	   
	   return queryFilter;
  }

}
