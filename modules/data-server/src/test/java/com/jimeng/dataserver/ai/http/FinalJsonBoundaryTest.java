package com.jimeng.dataserver.ai.http;
import com.jimeng.dataserver.ai.http.tool.HttpToolResults;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import java.util.*;
import java.nio.charset.StandardCharsets;
class FinalJsonBoundaryTest {
 @org.junit.jupiter.api.Test
 @SuppressWarnings("unchecked") void bothAdaptersCannotReconstructKey() {
  String key="\\\\token123";
  byte[] raw="\\token123".getBytes(StandardCharsets.UTF_8);
  var scrubber=new KeyScrubber(List.of(new SkillKey("TOKEN",7L,"test",List.of("test.invalid"),null,null,key)));
  var result=new HttpGatewayResult(200,List.of(Map.entry("content-type","text/plain")),scrubber.scrub(new String(raw, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),false,null,null);
  Map<String,Object> payload=HttpToolResults.toModelPayload(result,false,20000);
  var tool=new ToolExecutionResult("call1","http_request",true,payload);
  Map<String,Object> oa=new LinkedHashMap<>();
  List<Object> oam=new ArrayList<>(); oa.put("messages",oam);
  var response=Map.<String,Object>of("choices",List.of(Map.of("message",Map.of("role","assistant","tool_calls",List.of(Map.of("id","call1","type","function","function",Map.of("name","http_request","arguments","{}")))))));
  new OpenAiProtocolAdapter().appendToolResultTurn(oa,response,List.of(tool));
  String oat=(String)((Map<String,Object>)oam.get(oam.size()-1)).get("content");
  Map<String,Object> ca=new LinkedHashMap<>(); List<Object> cam=new ArrayList<>(); ca.put("messages",cam);
  new ClaudeProtocolAdapter().appendToolResultTurn(ca,Map.of("content",List.of(Map.of("type","tool_use","id","call1","name","http_request","input",Map.of()))),List.of(tool));
  var blocks=(List<Map<String,Object>>)((Map<String,Object>)cam.get(cam.size()-1)).get("content");
  String cat=(String)blocks.get(0).get("content");
  org.assertj.core.api.Assertions.assertThat(payload.toString()).doesNotContain(key);
  org.assertj.core.api.Assertions.assertThat(oat).doesNotContain(key);
  org.assertj.core.api.Assertions.assertThat(cn.hutool.json.JSONUtil.toJsonStr(oa)).doesNotContain(key);
  org.assertj.core.api.Assertions.assertThat(cat).doesNotContain(key);
  org.assertj.core.api.Assertions.assertThat(cn.hutool.json.JSONUtil.toJsonStr(ca)).doesNotContain(key);
 }
}
