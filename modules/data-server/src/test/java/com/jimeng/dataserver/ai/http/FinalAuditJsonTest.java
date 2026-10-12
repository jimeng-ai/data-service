package com.jimeng.dataserver.ai.http;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
class FinalAuditJsonTest {
 @org.junit.jupiter.api.Test
 void logstashCannotReconstructKey() {
  String key="\\\\token123";
  var scrubber=new KeyScrubber(List.of(new SkillKey("TOKEN",7L,"test",List.of("test.invalid"),null,null,key)));
  var logging=(LoggerContext)LoggerFactory.getILoggerFactory();
  Logger logger=logging.getLogger("jm.http-gateway.audit"); logger.setAdditive(false);
  logger.setLevel(ch.qos.logback.classic.Level.INFO);
  var encoded=new ByteArrayOutputStream();
  var encoder=new LogstashEncoder(); encoder.setContext(logging); encoder.start();
  var appender=new OutputStreamAppender<ILoggingEvent>(); appender.setContext(logging); appender.setEncoder(encoder); appender.setOutputStream(encoded); appender.start(); logger.addAppender(appender);
  var ctx=new HttpGatewayContext("test",7L,"fake-run",HttpGatewayContext.Plane.TOOL,List.of());
  var req=new HttpGatewayRequest("GET","https://test.invalid/\\token123",List.of(),null);
  var result=new HttpGatewayResult(200,List.of(),new byte[0],false,null,null);
  HttpGatewayAudit.record(ctx,req,result,List.of("test"),0,System.nanoTime(),scrubber);
  appender.stop(); encoder.stop();
  String output=encoded.toString(StandardCharsets.UTF_8);
  logger.detachAppender(appender); logger.setAdditive(true);
  org.assertj.core.api.Assertions.assertThat(output).doesNotContain(key);
 }
}
