/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mypackage.pipeline;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link BatchUserTrafficPipeline} is a sample pipeline which can be used as a base for creating a real
 * Dataflow pipeline.
 *
 * <p><b>Pipeline Requirements</b>
 *
 * <ul>
 *   <li>Requirement #1
 *   <li>Requirement #2
 * </ul>
 *
 * <p><b>Example Usage</b>
 *
 * <pre>
 * # Set the pipeline vars
 * PROJECT_ID=PROJECT_ID
 * PIPELINE_FOLDER=gs://${PROJECT_ID}/dataflow/pipelines/sample-pipeline
 *
 * # Set the runner
 * RUNNER=DataflowRunner
 *
 * # Build the template
 * mvn compile exec:java \
 * -Dexec.mainClass=com.mypackage.pipeline.BatchUserTrafficPipeline \
 * -Dexec.cleanupDaemonThreads=false \
 * -Dexec.args=" \
 * --project=${PROJECT_ID} \
 * --stagingLocation=${PIPELINE_FOLDER}/staging \
 * --tempLocation=${PIPELINE_FOLDER}/temp \
 * --runner=${RUNNER} \
 * ADDITIONAL PARAMETERS HERE"
 * </pre>
 */
public class BatchUserTrafficPipeline {

  /*
   * The logger to output status messages to.
   */
  private static final Logger LOG = LoggerFactory.getLogger(BatchUserTrafficPipeline.class);

  /**
   * The {@link Options} class provides the custom execution options passed by the executor at the
   * command-line.
   */
  public interface Options extends PipelineOptions {
    @Description("Path to events.json")
    String getInputPath();
    void setInputPath(String inputPath);

    @Description("BigQuery table name")
    String getTableName();
    void setTableName(String tableName);
  }

  @VisibleForTesting
  /**
   * A class used for parsing JSON web server events
   */
  public static class CommonLog {
    String user_id;
    String ip;
    double lat;
    double lng;
    String timestamp;
    String http_request;
    String user_agent;
    int http_response;
    int num_bytes;

    CommonLog(String user_id, String ip, double lat, double lng, String timestamp,
              String http_request, String user_agent, int http_response, int num_bytes) {
      this.user_id = user_id;
      this.ip = ip;
      this.lat = lat;
      this.lng = lng;
      this.timestamp = timestamp;
      this.http_request = http_request;
      this.user_agent = user_agent;
      this.http_response = http_response;
      this.num_bytes = num_bytes;
    }
  }

  @VisibleForTesting
  /**
   * A DoFn which accepts a JSON string outputs a instance of TableRow
   */
  static class JsonToTableRowFn extends DoFn<String, TableRow> {

    @ProcessElement
    public void processElement(@Element String json, OutputReceiver<TableRow> r) throws Exception {
      Gson gson = new Gson();
      CommonLog commonLog = gson.fromJson(json, CommonLog.class);
      TableRow row = new TableRow();
      row.set("user_id", commonLog.user_id);
      row.set("ip", commonLog.ip);
      row.set("lat", commonLog.lat);
      row.set("lng", commonLog.lng);
      row.set("event_timestamp", Instant.parse(commonLog.timestamp).toString());
      row.set("http_request", commonLog.http_request);

      r.output(row);
    }
  }

  /**
   * The main entry-point for pipeline execution. This method will start the pipeline but will not
   * wait for it's execution to finish. If blocking execution is required, use the {@link
   * BatchUserTrafficPipeline#run(Options)} method to start the pipeline and invoke
   * {@code result.waitUntilFinish()} on the {@link PipelineResult}.
   *
   * @param args The command-line args passed by the executor.
   */
  public static void main(String[] args) {
    PipelineOptionsFactory.register(Options.class);
    Options options = PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(Options.class);
    run(options);
  }

  /**
   * Runs the pipeline to completion with the specified options. This method does not wait until the
   * pipeline is finished before returning. Invoke {@code result.waitUntilFinish()} on the result
   * object to block until the pipeline is finished running if blocking programmatic execution is
   * required.
   *
   * @param options The execution options.
   * @return The pipeline result.
   */
  public static PipelineResult run(Options options) {

    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);
    options.setJobName("sample-pipeline-" + System.currentTimeMillis());
    options.setRunner(DataflowRunner.class);

    List<TableFieldSchema> fields = new ArrayList<>();
    fields.add(new TableFieldSchema().setName("user_id").setType("STRING"));
    fields.add(new TableFieldSchema().setName("hits").setType("INTEGER"));
    TableSchema schema = new TableSchema().setFields(fields);


    /*
     * Steps:
     *  1) Read something
     *  2) Transform something
     *  3) Write something
     */

    LOG.info("Building pipeline...");

    pipeline
            .apply("ReadFromGCS", TextIO.read().from(options.getInputPath()))
            .apply("ParseCommonLog", ParDo.of(new DoFn<String, CommonLog>() {
              @ProcessElement
              public void processElement(@Element String line, OutputReceiver<CommonLog> r) {
                Gson gson = new Gson();
                CommonLog commonLog = gson.fromJson(line, CommonLog.class);
                r.output(commonLog);
              }
            }))
            .apply("MapToKV", ParDo.of(new DoFn<CommonLog, KV<String, Integer>>() {
              @ProcessElement
              public void processElement(@Element CommonLog commonLog, OutputReceiver<KV<String, Integer>> r) {
                r.output(KV.of(commonLog.user_id, 1));
              }
            }))
            .apply("HitsPerUser", Sum.integersPerKey())
            .apply("ConvertToTableRow", ParDo.of(new DoFn<KV<String, Integer>, TableRow>() {
              @ProcessElement
              public void processElement(@Element KV<String, Integer> kv, OutputReceiver<TableRow> receiver) {
                TableRow tableRow = new TableRow();
                tableRow.set("user_id", kv.getKey());
                tableRow.set("hits", kv.getValue());
                receiver.output(tableRow);
              }
            }))
            .apply("WriteToBQ", BigQueryIO.writeTableRows()
                    .to(options.getTableName())
                    .withSchema(schema)
                    .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                    .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

    return pipeline.run();
  }
}
