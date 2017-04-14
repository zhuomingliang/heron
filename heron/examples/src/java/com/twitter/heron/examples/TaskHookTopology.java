// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.examples;


import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.hooks.ITaskHook;
import org.apache.storm.hooks.info.BoltAckInfo;
import org.apache.storm.hooks.info.BoltExecuteInfo;
import org.apache.storm.hooks.info.BoltFailInfo;
import org.apache.storm.hooks.info.EmitInfo;
import org.apache.storm.hooks.info.SpoutAckInfo;
import org.apache.storm.hooks.info.SpoutFailInfo;
import org.apache.storm.metric.api.GlobalMetrics;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;

public final class TaskHookTopology {

  private TaskHookTopology() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new RuntimeException("Specify topology name");
    }
    TopologyBuilder builder = new TopologyBuilder();

    builder.setSpout("word", new AckingTestWordSpout(), 2);
    builder.setBolt("count", new CountBolt(), 2)
        .shuffleGrouping("word");

    Config conf = new Config();
    conf.setDebug(true);
    // Put an arbitrary large number here if you don't want to slow the topology down
    conf.setMaxSpoutPending(1000 * 1000 * 1000);
    // To enable acking, we need to setEnableAcking true
    conf.setEnableAcking(true);
    conf.put(Config.TOPOLOGY_WORKER_CHILDOPTS, "-XX:+HeapDumpOnOutOfMemoryError");

    // Set the task hook
    List<String> taskHooks = new LinkedList<>();
    taskHooks.add("com.twitter.heron.examples.TaskHookTopology$TestTaskHook");
    conf.setAutoTaskHooks(taskHooks);

    conf.setNumStmgrs(1);
    StormSubmitter.submitTopology(args[0], conf, builder.createTopology());
  }

  public static class TestTaskHook implements ITaskHook {
    private static final long N = 10000;
    private long emitted = 0;
    private long spoutAcked = 0;
    private long spoutFailed = 0;
    private long boltExecuted = 0;
    private long boltAcked = 0;
    private long boltFailed = 0;

    private String constructString;

    public TestTaskHook() {
      this.constructString = "This TestTaskHook is constructed with no parameters";
    }

    public TestTaskHook(String constructString) {
      this.constructString = constructString;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map conf, TopologyContext context) {
      GlobalMetrics.incr("hook_prepare");
      System.out.println(constructString);
      System.out.println("prepare() is invoked in hook");
      System.out.println(conf);
      System.out.print(context);
    }

    @Override
    public void cleanup() {
      GlobalMetrics.incr("hook_cleanup");
      System.out.println("clean() is invoked in hook");
    }

    @Override
    public void emit(EmitInfo info) {
      GlobalMetrics.incr("hook_emit");
      ++emitted;
      if (emitted % N == 0) {
        System.out.println("emit() is invoked in hook");
        System.out.println(info.values);
        System.out.println(info.taskId);
        System.out.println(info.stream);
        System.out.println(info.outTasks);
      }
    }

    @Override
    public void spoutAck(SpoutAckInfo info) {
      GlobalMetrics.incr("hook_spoutAck");
      ++spoutAcked;
      if (spoutAcked % N == 0) {
        System.out.println("spoutAck() is invoked in hook");
        System.out.println(info.completeLatencyMs);
        System.out.println(info.messageId);
        System.out.println(info.spoutTaskId);
      }
    }

    @Override
    public void spoutFail(SpoutFailInfo info) {
      GlobalMetrics.incr("hook_spoutFail");
      ++spoutFailed;
      if (spoutFailed % N == 0) {
        System.out.println("spoutFail() is invoked in hook");
        System.out.println(info.failLatencyMs);
        System.out.println(info.messageId);
        System.out.println(info.spoutTaskId);
      }
    }

    @Override
    public void boltExecute(BoltExecuteInfo info) {
      GlobalMetrics.incr("hook_boltExecute");
      ++boltExecuted;
      if (boltExecuted % N == 0) {
        System.out.println("boltExecute() is invoked in hook");
        System.out.println(info.executeLatencyMs);
        System.out.println(info.executingTaskId);
        System.out.println(info.tuple);
      }
    }

    @Override
    public void boltAck(BoltAckInfo info) {
      GlobalMetrics.incr("hook_boltAck");
      ++boltAcked;
      if (boltAcked % N == 0) {
        System.out.println("boltAck() is invoked in hook");
        System.out.println(info.ackingTaskId);
        System.out.println(info.processLatencyMs);
        System.out.println(info.tuple);
      }
    }

    @Override
    public void boltFail(BoltFailInfo info) {
      GlobalMetrics.incr("hook_boltFail");
      ++boltFailed;
      if (boltFailed % N == 0) {
        System.out.println("boltFail() is invoked in hook");
        System.out.println(info.failingTaskId);
        System.out.println(info.failLatencyMs);
        System.out.println(info.tuple);
      }
    }
  }

  public static class AckingTestWordSpout extends BaseRichSpout {
    private static final long serialVersionUID = 6702214894823377325L;
    private SpoutOutputCollector collector;
    private String[] words;
    private Random rand;

    public AckingTestWordSpout() {
    }

    @SuppressWarnings("rawtypes")
    public void open(
        Map conf,
        TopologyContext context,
        SpoutOutputCollector acollector) {
      collector = acollector;
      words = new String[]{"nathan", "mike", "jackson", "golda", "bertels"};
      rand = new Random();

      // Add task hook dynamically
      context.addTaskHook(
          new TestTaskHook("This TestTaskHook is constructed by AckingTestWordSpout"));
    }

    public void close() {
    }

    public void nextTuple() {
      // We explicitly slow down the spout to avoid the stream mgr to be the bottleneck
      Utils.sleep(1);
      final String word = words[rand.nextInt(words.length)];
      // To enable acking, we need to emit tuple with MessageId, which is an object
      collector.emit(new Values(word), word);
    }

    public void ack(Object msgId) {
    }

    public void fail(Object msgId) {
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
      declarer.declare(new Fields("word"));
    }
  }

  public static class CountBolt extends BaseRichBolt {
    private static final long serialVersionUID = 851874677718634075L;
    private OutputCollector collector;
    private long nItems;
    private long startTime;

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(
        Map conf,
        TopologyContext context,
        OutputCollector acollector) {
      collector = acollector;
      nItems = 0;
      startTime = System.currentTimeMillis();

      // Add task hook dynamically
      context.addTaskHook(new TestTaskHook("This TestTaskHook is constructed by CountBolt"));

    }

    @Override
    public void execute(Tuple tuple) {
      // We need to ack a tuple when we consider it is done successfully
      // Or we could fail it by invoking collector.fail(tuple)
      // If we do not do the ack or fail explicitly
      // After the MessageTimeout Seconds, which could be set in Config, the spout will
      // fail this tuple
      ++nItems;
      if (nItems % 10000 == 0) {
        long latency = System.currentTimeMillis() - startTime;
        System.out.println("Bolt processed " + nItems + " tuples in " + latency + " ms");
        GlobalMetrics.incr("selected_items");
        // Here we explicitly forget to do the ack or fail
        // It would trigger fail on this tuple on spout end after MessageTimeout Seconds
      } else if (nItems % 2 == 0) {
        collector.fail(tuple);
      } else {
        collector.ack(tuple);
      }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
    }
  }
}
