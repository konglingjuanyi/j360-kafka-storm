package me.j360.kafka.storm.trident;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.StormTopology;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.tuple.Fields;
import storm.kafka.KafkaConfig;
import storm.kafka.StringScheme;
import storm.kafka.trident.OpaqueTridentKafkaSpout;
import storm.kafka.trident.TridentKafkaConfig;
import storm.trident.Stream;
import storm.trident.TridentTopology;

import java.util.Arrays;

/**
 * Package: me.j360.kafka.storm.trident
 * User: min_xu
 * Date: 16/7/28 下午11:20
 * 说明：
 */
public class LogAnalysisTopology {

    public static StormTopology buildTopology(){
        TridentTopology topology = new TridentTopology();
        KafkaConfig.StaticHosts kafkaHosts=KafkaConfig.StaticHosts.fromHostString(
                Arrays.asList(new String[]{"testserver"}), 1);
        TridentKafkaConfig spoutConf = new TridentKafkaConfig(kafkaHosts,"log-analysis");
        //spoutConf.scheme= newStringScheme();
        spoutConf.scheme =new SchemeAsMultiScheme(new StringScheme());
        spoutConf.forceStartOffsetTime(-1);
        OpaqueTridentKafkaSpout spout = new OpaqueTridentKafkaSpout(spoutConf);
        Stream spoutStream= topology.newStream("kafka-stream", spout);
        Fields jsonFields =new Fields("level","timestamp","message","logger");
        Stream parsedStream=spoutStream.each(new
                Fields("str"),new JsonProjectFunction(jsonFields), jsonFields);
        // drop theunparsed JSON to reducetuple size
        parsedStream=parsedStream.project(jsonFields);
        EWMA ewma = new EWMA().sliding(1.0,
                EWMA.Time.MINUTES).withAlpha(EWMA.ONE_MINUTE_ALPHA);
        Stream averageStream = parsedStream.each(new Fields("timestamp"),
                new MovingAverageFunction(ewma,
                EWMA.Time.MINUTES),new Fields("average"));
        ThresholdFilterFunction tff = new ThresholdFilterFunction(50D);
        Stream thresholdStream =averageStream.each(new Fields("average"), tff,
                new Fields("change","threshold"));
        Stream filteredStream =
        thresholdStream.each(new Fields("change"), new BooleanFilter());
        filteredStream.each(filteredStream.getOutputFields(),
                new XMPPFunction(new NotifyMessageMapper()), new Fields());
        return topology.build();

    }

    public static void main(String[]args)throws
    Exception {
        Config conf = new Config();
        conf.put(XMPPFunction.XMPP_USER,"storm@budreau.local");
        conf.put(XMPPFunction.XMPP_PASSWORD,"storm");
        conf.put(XMPPFunction.XMPP_SERVER,"budreau.local");
        conf.put(XMPPFunction.XMPP_TO,"tgoetz@budreau.local");
        conf.setMaxSpoutPending(5);
        if (args.length ==0) {
            LocalCluster cluster = new LocalCluster();
            cluster.submitTopology("log-analysis", conf, buildTopology());
            } else {
            conf.setNumWorkers(3);
            StormSubmitter.submitTopology(args[0],
                    conf, buildTopology());
            }
        }
}