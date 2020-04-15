/**
 *
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
package com.bufferedmutator.client;

import java.util.UUID;
import java.util.Random;
import java.lang.StringBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An example of using the {@link BufferedMutator} interface.
 */
@InterfaceAudience.Private
public class HbaseBufferedMutatorClient extends Configured implements Tool {

  private static final Logger LOG = LoggerFactory.getLogger(HbaseBufferedMutatorClient.class);

  private static final int POOL_SIZE = 10;
  private static final int TASK_COUNT = 1000;
  private static final TableName TABLE = TableName.valueOf("test");
  private static final byte[] FAMILY = Bytes.toBytes("family");
  private static final int DATA_SIZE = 350000;
  private static final String RANDOM_STRING = "dddddfffffhhhhh";
  private static final Random r = new Random();

  protected String buildKeyName() {
    //Building custom rowkey based on Acoustic data
    String uuid1 = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String uuid2 = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    long longNumber = (long) Math.floor(Math.random() * 9_000_000_000L) + 1_000_000_000L; //can't do Int at 10 digit
    return uuid1+"#"+RANDOM_STRING+"#"+String.format("%04d", r.nextInt(10000))+"#"+Long.toString(longNumber)+"#"+uuid2;
  }

  protected String buildValue() {
    StringBuilder sb = new StringBuilder(DATA_SIZE);
    for (int i=0; i<DATA_SIZE; i++) { //Building a string of 350 KB
      sb.append((char)(r.nextInt(26) + 'a'));
    }
    return sb.toString();
  }

  @Override
  public int run(String[] args) throws InterruptedException, ExecutionException, TimeoutException {

    /** a callback invoked when an asynchronous write fails. */
    final BufferedMutator.ExceptionListener listener = new BufferedMutator.ExceptionListener() {
      @Override
      public void onException(RetriesExhaustedWithDetailsException e, BufferedMutator mutator) {
        for (int i = 0; i < e.getNumExceptions(); i++) {
          LOG.info("Failed to sent put " + e.getRow(i) + ".");
        }
      }
    };
    BufferedMutatorParams params = new BufferedMutatorParams(TABLE)
        .listener(listener);

    //
    // step 1: create a single Connection and a BufferedMutator, shared by all worker threads.
    //
    try (final Connection conn = ConnectionFactory.createConnection(getConf());
         final BufferedMutator mutator = conn.getBufferedMutator(params)) {

      /** worker pool that operates on BufferedTable instances */
      final ExecutorService workerPool = Executors.newFixedThreadPool(POOL_SIZE);
      List<Future<Void>> futures = new ArrayList<>(TASK_COUNT);
      final Random r = new Random();
      for (int i = 0; i < TASK_COUNT; i++) {
        //final int j=i;
        futures.add(workerPool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            //
            // step 2: each worker sends edits to the shared BufferedMutator instance. They all use
            // the same backing buffer, call-back "listener", and RPC executor pool.
            //
            String rowkey = buildKeyName();
            Put p = new Put(Bytes.toBytes(rowkey));
            p.addColumn(FAMILY, Bytes.toBytes("rq"), Bytes.toBytes(""));
            p.addColumn(FAMILY, Bytes.toBytes("rs"), Bytes.toBytes(buildValue()));
            p.addColumn(FAMILY, Bytes.toBytes("hn"), Bytes.toBytes(""));
            p.addColumn(FAMILY, Bytes.toBytes("hd"), Bytes.toBytes(UUID.randomUUID().toString().replace("-", "").toUpperCase()));
            p.addColumn(FAMILY, Bytes.toBytes("so"), Bytes.toBytes(""));
            p.addColumn(FAMILY, Bytes.toBytes("eo"), Bytes.toBytes(""));
            p.addColumn(FAMILY, Bytes.toBytes("tp"), Bytes.toBytes(""));
            mutator.mutate(p);
           /* if(j > 0 && j % 6 == 0) { //flush edits every 2 MB or 6 PUTs but for now, controlling using hbase.client.write.buffer
              mutator.flush();
              // do work... maybe you want to call mutator.flush() after many edits to ensure any of
              // this worker's edits are sent before exiting the Callable
            } */
            return null;
         }
        }));
      }

      //
      // step 3: clean up the worker pool, shut down.
      //
      for (Future<Void> f : futures) {
        f.get(5, TimeUnit.MINUTES);
      }
      workerPool.shutdown();
      mutator.close();
    } catch (IOException e) {
      // exception while creating/destroying Connection or BufferedMutator
      LOG.info("exception while creating/destroying Connection or BufferedMutator", e);
    } // BufferedMutator.close() ensures all work is flushed. Could be the custom listener is
      // invoked from here.
    return 0;
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new HbaseBufferedMutatorClient(), args);

  }
}
