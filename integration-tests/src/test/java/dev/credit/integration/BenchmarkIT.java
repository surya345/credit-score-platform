package dev.credit.integration;

import static dev.credit.integration.TestPlatform.JSON;
import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

/** Opt-in, closed-loop local measurement. Does not establish production capacity. */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class BenchmarkIT {
  final Map<String,Object> result = new LinkedHashMap<>();
  final List<Map<String,Object>> phases = new ArrayList<>();
  TestPlatform p;

  @Test void measureLocalBackend() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("bench.enabled"), "Opt-in benchmark; use -Dbench.enabled=true");
    p = new TestPlatform();
    Path output = Path.of(System.getProperty("bench.output", p.root.resolve("docs/benchmark-results.json").toString()));
    result.put("status", "running"); result.put("startedAtUtc", Instant.now().toString());
    result.put("java", System.getProperty("java.runtime.version"));
    result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    result.put("cpuQuota", readSystemFile("/sys/fs/cgroup/cpu.max"));
    result.put("memoryLimitBytes", readSystemFile("/sys/fs/cgroup/memory.max"));
    result.put("method", Map.of("type", "closed-loop; fixed in-flight workers; no think time",
        "workflowSample", TestPlatform.SAMPLE, "warmupWorkflows", 20, "measuredWorkflows", 270,
        "outboxPollMs", 200, "visibilityPollMs", 100,
        "httpRoute", "authenticated gateway to first service instance; two asynchronous consumers per group",
        "scope", "same-host native MySQL/Kafka; synthetic JWT issuer; frontend and Keycloak excluded"));
    result.put("phases", phases);
    try {
      p.start();
      var warm = workflowPhase("warmup", 20, 5);
      var customers = samples(warm).stream().map(s -> (String)s.get("customerId")).toList();
      int[][] orders = {{1,5,10},{5,10,1},{10,1,5}};
      for (int round=0;round<orders.length;round++)
        for (int concurrency:orders[round]) workflowPhase("workflow-round-"+(round+1),30,concurrency);
      Map<String,Long> rows = Map.of(
          "financialRecords", p.count("data","SELECT COUNT(*) FROM financial_records"),
          "creditScores", p.count("scoring","SELECT COUNT(*) FROM credit_scores"),
          "creditReports", p.count("report","SELECT COUNT(*) FROM credit_reports"));
      result.put("finalRows", rows);
      for (long count:rows.values()) assertEquals(290L,count,"One financial, score and report row per workload submission");
      if (Boolean.getBoolean("bench.redis.enabled")) {
        int index=0;
        for (boolean cache:new boolean[]{false,true,true,false}) {
          p.stopService("scoring-1");
          p.startService("scoring-1",Map.of("app.cache.enabled",String.valueOf(cache),
              "spring.data.redis.host","127.0.0.1","spring.data.redis.port",System.getProperty("bench.redis.port","16379")));
          p.awaitHealthy("scoring-1");
          var warmReads = parallel(200,10,i -> readScore(customers.get(i%customers.size())));
          assertEquals(0, failures(warmReads),"Read warmup errors");
          long hits=redisHits(), fetches=mysqlFetches();
          long begin=System.nanoTime();
          var reads=parallel(1000,10,i -> readScore(customers.get(i%customers.size())));
          double wall=ms(begin);
          var phase=phase("score-read-"+(++index),10,wall,reads,List.of("latencyMs"));
          phase.put("cacheEnabled",cache); phase.put("warmupRequests",200);
          long hitDelta=redisHits()-hits;
          phase.put("redisHitDelta",hitDelta); phase.put("mysqlScoreTableFetchDelta",mysqlFetches()-fetches);
          phases.add(phase);
          if(cache) assertEquals(1000L,hitDelta,"All measured reads must be real Redis hits");
          else assertEquals(0L,hitDelta,"Disabled phase must not hit Redis");
          System.out.println("BENCH " + phase.get("name") + " errors="+phase.get("errors")+" cache="+cache);
        }
      }
      long errors=phases.stream().mapToLong(x -> ((Number)x.get("errors")).longValue()).sum();
      result.put("totalErrors",errors); assertEquals(0L,errors,"Workload request errors");
      result.put("status","passed");
    } catch (Exception | AssertionError e) {
      result.put("status","failed"); result.put("failure",e.toString()); throw e;
    } finally {
      try { p.close(); result.put("cleanup","passed"); }
      catch (Exception e) { result.put("status","failed");result.put("cleanup",e.toString());throw e; }
      finally {
        result.put("finishedAtUtc",Instant.now().toString());
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
      }
    }
  }

  Map<String,Object> workflowPhase(String name,int count,int concurrency) throws Exception {
    List<String> customers=new ArrayList<>();
    for(int i=0;i<count;i++) customers.add(p.customer()); // outside measured window
    long begin=System.nanoTime();
    var raw=parallel(count,concurrency,i -> workflow(customers.get(i)));
    var phase=phase(name,concurrency,ms(begin),raw,List.of("submissionMs","scoreVisibleMs","reportVisibleMs","pdfDownloadMs","totalWorkflowMs"));
    phases.add(phase);
    System.out.println("BENCH "+name+" concurrency="+concurrency+" errors="+phase.get("errors"));
    assertEquals(0,failures(raw),"Workflow errors; inspect raw benchmark results");
    return phase;
  }
  Map<String,Object> workflow(String customer) {
    Map<String,Object> r=new LinkedHashMap<>();r.put("customerId",customer);
    String event=UUID.randomUUID().toString();r.put("eventId",event);
    long begin=System.nanoTime();
    try {
      p.submit(customer,event);r.put("submissionMs",ms(begin));
      JsonNode score=null,report=null;
      long deadline=begin+TimeUnit.SECONDS.toNanos(90);
      while(System.nanoTime()<deadline) {
        var response=p.request("gateway-1","GET","/api/scores/customers/"+customer+"/latest",null,null);
        if(response.statusCode()==200) {
          var node=JSON.readTree(response.body());
          if(node.path("sourceEventId").asText().equals(event)) {score=node;break;}
        } else assertEquals(404,response.statusCode(),"Unexpected score response");
        Thread.sleep(100);
      }
      assertNotNull(score,"Timed out waiting for score");
      assertEquals(763,score.path("result").path("score").asInt());r.put("scoreVisibleMs",ms(begin));
      while(System.nanoTime()<deadline) {
        var reports=p.api("GET","/api/reports/customers/"+customer,null,null,200).path("items");
        for(var node:reports) if(node.path("scoreId").asText().equals(score.path("id").asText())) {report=node;break;}
        if(report!=null) break;
        Thread.sleep(100);
      }
      assertNotNull(report,"Timed out waiting for report");r.put("reportVisibleMs",ms(begin));
      long pdfBegin=System.nanoTime();
      var pdf=p.request("gateway-1","GET","/api/reports/"+report.path("id").asText()+"/pdf",null,null);
      double pdfMs=ms(pdfBegin);
      assertEquals(200,pdf.statusCode()); assertTrue(pdf.body().length>1000);
      assertEquals("%PDF-",new String(pdf.body(),0,5,StandardCharsets.US_ASCII));
      r.put("pdfDownloadMs",pdfMs);r.put("pdfBytes",pdf.body().length);
      r.put("totalWorkflowMs",ms(begin));r.put("ok",true);
    } catch(Exception|AssertionError e) {r.put("ok",false);r.put("error",e.toString());}
    return r;
  }
  Map<String,Object> readScore(String customer) {
    Map<String,Object> r=new LinkedHashMap<>();long begin=System.nanoTime();
    try {
      var response=p.request("gateway-1","GET","/api/scores/customers/"+customer+"/latest",null,null);
      double elapsed=ms(begin);
      assertEquals(200,response.statusCode());
      assertEquals(763,JSON.readTree(response.body()).path("result").path("score").asInt());
      r.put("latencyMs",elapsed);r.put("ok",true);
    } catch(Exception|AssertionError e) {r.put("ok",false);r.put("error",e.toString());}
    return r;
  }
  static List<Map<String,Object>> parallel(int n,int workers,IntFunction<Map<String,Object>> task) throws Exception {
    try(var pool=Executors.newFixedThreadPool(workers)) {
      List<Future<Map<String,Object>>> futures=new ArrayList<>();
      for(int i=0;i<n;i++) {final int index=i;futures.add(pool.submit(() -> task.apply(index)));}
      List<Map<String,Object>> out=new ArrayList<>();
      for(var f:futures) out.add(f.get());return out;
    }
  }
  static Map<String,Object> phase(String name,int workers,double wall,List<Map<String,Object>> raw,List<String> metrics) {
    Map<String,Object> r=new LinkedHashMap<>();int failed=failures(raw);
    r.put("name",name);r.put("concurrency",workers);r.put("requests",raw.size());r.put("errors",failed);
    r.put("wallMs",wall);r.put("successfulOperationsPerSecond",(raw.size()-failed)*1000.0/wall);
    Map<String,Object> summary=new LinkedHashMap<>();
    for(var metric:metrics) {
      double[] values=raw.stream().filter(x -> Boolean.TRUE.equals(x.get("ok")))
          .mapToDouble(x -> ((Number)x.get(metric)).doubleValue()).sorted().toArray();
      if(values.length>0) summary.put(metric,Map.of("p50",percentile(values,.5),"p95",percentile(values,.95),
          "max",values[values.length-1],"mean",Arrays.stream(values).average().orElseThrow()));
    }
    r.put("summaryMs",summary);r.put("samples",raw);return r;
  }
  static int failures(List<Map<String,Object>> raw) {return (int)raw.stream().filter(x -> !Boolean.TRUE.equals(x.get("ok"))).count();}
  @SuppressWarnings("unchecked") static List<Map<String,Object>> samples(Map<String,Object> phase) {return (List<Map<String,Object>>)phase.get("samples");}
  static double percentile(double[] sorted,double fraction) {return sorted[Math.max(0,(int)Math.ceil(sorted.length*fraction)-1)];}
  static double ms(long start) {return (System.nanoTime()-start)/1_000_000.0;}
  static String readSystemFile(String path) {try{return Files.readString(Path.of(path)).trim();}catch(Exception e){return "unavailable";}}
  long mysqlFetches() {
    return p.count("scoring","SELECT COALESCE(SUM(COUNT_FETCH),0) FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA=? AND OBJECT_NAME='credit_scores'",p.databases.get("scoring"));
  }
  static long redisHits() throws Exception {
    try(var socket=new Socket("127.0.0.1",Integer.getInteger("bench.redis.port",16379))) {
      socket.setSoTimeout(3000);
      socket.getOutputStream().write("*2\r\n$4\r\nINFO\r\n$5\r\nstats\r\n".getBytes(StandardCharsets.US_ASCII));
      var in=socket.getInputStream();StringBuilder header=new StringBuilder();int b;
      while((b=in.read())!=-1 && b!='\n') header.append((char)b);
      if(header.isEmpty()||header.charAt(0)!='$') throw new IllegalStateException("Unexpected Redis INFO response");
      int len=Integer.parseInt(header.substring(1).trim());
      String body=new String(in.readNBytes(len),StandardCharsets.US_ASCII);
      for(String line:body.split("\r\n")) if(line.startsWith("keyspace_hits:")) return Long.parseLong(line.substring(14));
      throw new IllegalStateException("Missing Redis hit counter");
    }
  }
}
