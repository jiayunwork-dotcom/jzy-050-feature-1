package com.geotech.bearing.layered.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层剖面端到端 HTTP/JSON 集成测试：
 * 登记/列出/按名取出、点名核算、临时分层核算、折算过程摊开、
 * 分层结构错误（间隙/重叠/层厚/φ=90，且带层号）、与单层档独立互不影响、
 * 多份剖面 + 多次核算并发互不污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LayeredBearingApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> layer(double thickness, double c, double phi, double gamma) {
        Map<String, Object> m = new HashMap<>();
        m.put("thicknessM", thickness);
        m.put("cohesionKpa", c);
        m.put("frictionAngleDeg", phi);
        m.put("unitWeightKnM3", gamma);
        return m;
    }

    /** 填土 0-2 (10,10,18) / 原状土 2-5 (30,30,20) / 持力层 5-10 (5,25,19)。 */
    private List<Map<String, Object>> demoLayers() {
        return new ArrayList<>(List.of(
                layer(2, 10, 10, 18),
                layer(3, 30, 30, 20),
                layer(5, 5, 25, 19)));
    }

    private void assertReason(ResponseEntity<String> resp, String reason) {
        try {
            JsonNode node = json.readTree(resp.getBody());
            assertEquals(reason, node.get("error").asText(),
                    "错误原因码应为 " + reason + "，实际响应：" + resp.getBody());
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + resp.getBody(), e);
        }
    }

    // ---------- 登记 / 列出 / 按名取出 ----------

    @Test
    @DisplayName("登记分层剖面 -> 201；列出包含；按名取出原始分层列表（层序层厚指标一致）")
    void registerListAndGetLayeredProfile() throws Exception {
        String name = "layered-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "填土下卧原状土");
        body.put("layers", demoLayers());

        ResponseEntity<String> created =
                rest.postForEntity(url("/api/layered-profiles"), body, String.class);
        assertEquals(HttpStatus.CREATED.value(), created.getStatusCode().value(), created.getBody());

        ResponseEntity<String> listed = rest.getForEntity(url("/api/layered-profiles"), String.class);
        assertTrue(listed.getBody().contains(name), "列出结果应包含新登记分层剖面");

        ResponseEntity<String> got =
                rest.getForEntity(url("/api/layered-profiles/" + name), String.class);
        assertEquals(HttpStatus.OK.value(), got.getStatusCode().value());
        JsonNode node = json.readTree(got.getBody());
        assertEquals(3, node.get("layers").size());
        assertEquals(0, node.get("layers").get(0).get("layerIndex").asInt());
        assertEquals(2.0, node.get("layers").get(0).get("thicknessM").asDouble(), 1e-9);
        assertEquals(3.0, node.get("layers").get(1).get("thicknessM").asDouble(), 1e-9);
        assertEquals(30.0, node.get("layers").get(1).get("frictionAngleDeg").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("重复登记同名分层剖面 -> 409 LAYERED_PROFILE_ALREADY_EXISTS")
    void duplicateLayeredProfileConflicts() {
        String name = "layered-dup-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("layers", demoLayers());

        assertEquals(HttpStatus.CREATED.value(),
                rest.postForEntity(url("/api/layered-profiles"), body, String.class)
                        .getStatusCode().value());
        ResponseEntity<String> again =
                rest.postForEntity(url("/api/layered-profiles"), body, String.class);
        assertEquals(HttpStatus.CONFLICT.value(), again.getStatusCode().value());
        assertReason(again, "LAYERED_PROFILE_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("点名未登记分层剖面 -> 404 LAYERED_PROFILE_NOT_FOUND")
    void unknownLayeredProfileIsNotFound() {
        Map<String, Object> body = new HashMap<>();
        body.put("layeredProfileName", "no-such-layered-" + UUID.randomUUID());
        body.put("widthM", 4.0);
        body.put("depthM", 1.0);
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatusCode().value());
        assertReason(resp, "LAYERED_PROFILE_NOT_FOUND");
    }

    // ---------- 结构校验：间隙 / 重叠 / 层厚 / φ=90，且必须带层号 ----------

    @Test
    @DisplayName("登记：层厚非正 -> 400 LAYER_THICKNESS_NOT_POSITIVE，layerIndex 指到第 2 层（0 基=1）")
    void nonPositiveThicknessRejectedOnRegistrationWithLayerIndex() {
        String name = "zero-thickness-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        // 间隙/重叠只能出现在绝对深度拼接上（登记侧层厚累加天然相接），
        // 这两类判据在 LayeredProfileValidatorTest 用绝对深度直接钉死；
        // HTTP 侧这里钉同样必须在折算前拦截的层厚非正。
        body.put("layers", List.of(layer(2, 10, 10, 18), layer(0, 30, 30, 20)));
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-profiles"), body, String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertReason(resp, "LAYER_THICKNESS_NOT_POSITIVE");
        assertEquals(1, jsonField(resp, "layerIndex"), "出问题的是第 2 层（0 基层号 1）");
    }

    @Test
    @DisplayName("临时分层核算：某层 φ=90 -> 400 FRICTION_ANGLE_OUT_OF_RANGE + layerIndex")
    void layerFrictionNinetyRejectedOverHttp() {
        Map<String, Object> body = new HashMap<>();
        body.put("layers", List.of(layer(2, 10, 10, 18), layer(3, 0, 90, 20)));
        body.put("widthM", 4.0);
        body.put("depthM", 1.0);
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertReason(resp, "FRICTION_ANGLE_OUT_OF_RANGE");
        assertEquals(1, jsonField(resp, "layerIndex"));
    }

    @Test
    @DisplayName("临时分层缺字段 -> 400 且指出缺的是第几层的哪个字段")
    void missingLayerFieldRejectedWithLayerIndex() throws Exception {
        Map<String, Object> badLayer = new HashMap<>();
        badLayer.put("thicknessM", 3.0);
        badLayer.put("cohesionKpa", 30);
        // 缺 frictionAngleDeg
        badLayer.put("unitWeightKnM3", 20);
        Map<String, Object> body = new HashMap<>();
        body.put("layers", List.of(layer(2, 10, 10, 18), badLayer));
        body.put("widthM", 4.0);
        body.put("depthM", 1.0);

        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertReason(resp, "LAYER_FRICTION_ANGLE_MISSING");
        assertEquals(1, jsonField(resp, "layerIndex"));
    }

    @Test
    @DisplayName("埋深超过剖面底面 -> 400 EMBEDMENT_BELOW_PROFILE")
    void embedmentBelowProfileRejectedOverHttp() {
        Map<String, Object> body = new HashMap<>();
        body.put("layers", demoLayers());
        body.put("widthM", 4.0);
        body.put("depthM", 10.0); // 剖面底面 10m
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertReason(resp, "EMBEDMENT_BELOW_PROFILE");
    }

    @Test
    @DisplayName("同时给点名与临时分层 -> 400 LAYERED_SOURCE_AMBIGUOUS")
    void bothNameAndInlineLayersRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("layeredProfileName", "whatever");
        body.put("layers", demoLayers());
        body.put("widthM", 4.0);
        body.put("depthM", 1.0);
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), resp.getStatusCode().value());
        assertReason(resp, "LAYERED_SOURCE_AMBIGUOUS");
    }

    // ---------- 核算：折算过程摊开 + tan 加权 + 退化 + 与单层路径一致 ----------

    @Test
    @DisplayName("点名核算：返回窗口、每层贡献、等效指标与最终 qu；φ 为 tan 加权反算而非算术平均")
    void namedCalculationExposesReductionAndUsesTanWeighting() throws Exception {
        String name = "named-calc-" + UUID.randomUUID();
        Map<String, Object> reg = new HashMap<>();
        reg.put("name", name);
        reg.put("layers", demoLayers());
        assertEquals(HttpStatus.CREATED.value(),
                rest.postForEntity(url("/api/layered-profiles"), reg, String.class)
                        .getStatusCode().value());

        Map<String, Object> calc = new HashMap<>();
        calc.put("layeredProfileName", name);
        calc.put("widthM", 4.0);
        calc.put("depthM", 1.0);
        calc.put("shape", "STRIP");
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), calc, String.class);
        assertEquals(HttpStatus.OK.value(), resp.getStatusCode().value(), resp.getBody());

        JsonNode root = json.readTree(resp.getBody());
        assertEquals("PROFILE", root.get("source").asText());
        assertEquals(name, root.get("profileName").asText());

        // 窗口 [1,3]
        assertEquals(1.0, root.get("window").get("topDepthM").asDouble(), 1e-9);
        assertEquals(3.0, root.get("window").get("bottomDepthM").asDouble(), 1e-9);
        assertEquals(2.0, root.get("window").get("lengthM").asDouble(), 1e-9);
        assertEquals(false, root.get("windowTruncated").asBoolean());
        assertEquals(2.0, root.get("actualWindowThicknessM").asDouble(), 1e-9);

        // 每层贡献摊开：1m / 1m / 0m
        JsonNode contributions = root.get("layerContributions");
        assertEquals(1.0, contributions.get(0).get("contributedThicknessM").asDouble(), 1e-9);
        assertEquals(true, contributions.get(0).get("participating").asBoolean());
        assertEquals(1.0, contributions.get(1).get("contributedThicknessM").asDouble(), 1e-9);
        assertEquals(0.0, contributions.get(2).get("contributedThicknessM").asDouble(), 1e-9);
        assertEquals(false, contributions.get(2).get("participating").asBoolean());

        // 等效 c、γ 算术加权；φ 是 tan 加权反算
        JsonNode eq = root.get("equivalentSoil");
        assertEquals(20.0, eq.get("cohesionKpa").asDouble(), 1e-9);
        assertEquals(19.0, eq.get("unitWeightKnM3").asDouble(), 1e-9);
        double expectedPhi = Math.toDegrees(Math.atan(
                (Math.tan(Math.toRadians(10)) + Math.tan(Math.toRadians(30))) / 2.0));
        assertEquals(expectedPhi, eq.get("frictionAngleDeg").asDouble(), 1e-9,
                "φ_eq 必须为 tan 加权反算值");
        assertNotEquals(20.0, eq.get("frictionAngleDeg").asDouble(), 1e-9,
                "绝不能是错误的角度算术平均 20°");

        // 内嵌 bearing 为等效指标走既有路径的完整结果；其 factors 与 φ_eq 对应
        JsonNode bearing = root.get("bearing");
        assertEquals("EQUIVALENT", bearing.get("source").asText());
        assertTrue(bearing.get("factors").get("nq").asDouble() > 0);
        assertTrue(root.get("bearing").get("terms").get("quKpa").asDouble() > 0);
    }

    @Test
    @DisplayName("退化：第一层单独填满窗口时等效指标等于第一层，qu 与同参数单层核算一致")
    void singleLayerFillingWindowMatchesSingleLayerCalculation() throws Exception {
        Map<String, Object> layeredBody = new HashMap<>();
        layeredBody.put("layers", demoLayers());
        layeredBody.put("widthM", 4.0); // 窗口 [0,2]
        layeredBody.put("depthM", 0.0);
        ResponseEntity<String> layeredResp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), layeredBody, String.class);
        assertEquals(HttpStatus.OK.value(), layeredResp.getStatusCode().value(), layeredResp.getBody());
        JsonNode root = json.readTree(layeredResp.getBody());
        JsonNode eq = root.get("equivalentSoil");
        assertEquals(10.0, eq.get("cohesionKpa").asDouble(), 0.0);
        assertEquals(10.0, eq.get("frictionAngleDeg").asDouble(), 0.0);
        assertEquals(18.0, eq.get("unitWeightKnM3").asDouble(), 0.0);

        // 用既有单层核算接口对第一层参数 + 同几何直接核算，qu 必须一致
        Map<String, Object> singleBody = new HashMap<>();
        Map<String, Object> soil = new HashMap<>();
        soil.put("cohesionKpa", 10);
        soil.put("frictionAngleDeg", 10);
        soil.put("unitWeightKnM3", 18);
        singleBody.put("soil", soil);
        singleBody.put("widthM", 4.0);
        singleBody.put("depthM", 0.0);
        ResponseEntity<String> singleResp =
                rest.postForEntity(url("/api/bearings/calculate"), singleBody, String.class);
        assertEquals(HttpStatus.OK.value(), singleResp.getStatusCode().value());
        double singleQu = json.readTree(singleResp.getBody()).get("terms").get("quKpa").asDouble();
        double layeredQu = root.get("bearing").get("terms").get("quKpa").asDouble();
        assertEquals(singleQu, layeredQu, 0.0, "退化情形 qu 必须与单层路径严格一致");
    }

    @Test
    @DisplayName("窗口被剖面底面截断：truncated=true，实际厚度小于名义窗口，仅用可取到的层")
    void truncatedWindowReportedOverHttp() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("layers", demoLayers());
        body.put("widthM", 12.0); // 窗口 [6,12]
        body.put("depthM", 6.0);
        ResponseEntity<String> resp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
        assertEquals(HttpStatus.OK.value(), resp.getStatusCode().value(), resp.getBody());
        JsonNode root = json.readTree(resp.getBody());
        assertEquals(true, root.get("windowTruncated").asBoolean());
        assertEquals(6.0, root.get("window").get("lengthM").asDouble(), 1e-9, "名义窗口 6m");
        assertEquals(4.0, root.get("actualWindowThicknessM").asDouble(), 1e-9, "实际只取 4m");
        assertEquals(5.0, root.get("equivalentSoil").get("cohesionKpa").asDouble(), 0.0,
                "只剩第 3 层，退化为第 3 层指标");
    }

    @Test
    @DisplayName("埋深下移切换窗口起点：Df=1 与 Df=3 给出不同等效指标与 qu")
    void shiftingEmbedmentChangesReduction() throws Exception {
        double[] qus = new double[2];
        double[] phis = new double[2];
        double[] depths = {1.0, 3.0};
        for (int i = 0; i < 2; i++) {
            Map<String, Object> body = new HashMap<>();
            body.put("layers", demoLayers());
            body.put("widthM", 4.0);
            body.put("depthM", depths[i]);
            ResponseEntity<String> resp =
                    rest.postForEntity(url("/api/layered-bearings/calculate"), body, String.class);
            assertEquals(HttpStatus.OK.value(), resp.getStatusCode().value(), resp.getBody());
            JsonNode root = json.readTree(resp.getBody());
            assertEquals(depths[i], root.get("window").get("topDepthM").asDouble(), 1e-9,
                    "窗口起点必须随埋深下移");
            qus[i] = root.get("bearing").get("terms").get("quKpa").asDouble();
            phis[i] = root.get("equivalentSoil").get("frictionAngleDeg").asDouble();
        }
        assertNotEquals(phis[0], phis[1], 1e-9, "[1,3] 跨两层 vs [3,5] 全在第 2 层，φ 必须不同");
        assertEquals(30.0, phis[1], 0.0, "Df=3 窗口 [3,5] 全在第 2 层，φ 退化为 30°");
        assertNotEquals(qus[0], qus[1], 1e-6);
    }

    // ---------- 与单层档独立 ----------

    @Test
    @DisplayName("分层剖面与单层参数档各自独立：同名可两边共存，点名互不串台")
    void layeredAndSingleProfilesAreIndependentNamespaces() throws Exception {
        String sharedName = "shared-name-" + UUID.randomUUID();

        // 单层档
        Map<String, Object> single = new HashMap<>();
        single.put("name", sharedName);
        single.put("cohesionKpa", 30);
        single.put("frictionAngleDeg", 0);
        single.put("unitWeightKnM3", 18);
        assertEquals(HttpStatus.CREATED.value(),
                rest.postForEntity(url("/api/profiles"), single, String.class).getStatusCode().value());

        // 同名分层剖面也能登记
        Map<String, Object> layered = new HashMap<>();
        layered.put("name", sharedName);
        layered.put("layers", demoLayers());
        assertEquals(HttpStatus.CREATED.value(),
                rest.postForEntity(url("/api/layered-profiles"), layered, String.class)
                        .getStatusCode().value());

        // 两边各自点名取出的形态完全不同、互不覆盖
        JsonNode singleGot = json.readTree(
                rest.getForEntity(url("/api/profiles/" + sharedName), String.class).getBody());
        assertEquals(30.0, singleGot.get("cohesionKpa").asDouble(), 1e-9);

        JsonNode layeredGot = json.readTree(
                rest.getForEntity(url("/api/layered-profiles/" + sharedName), String.class).getBody());
        assertEquals(3, layeredGot.get("layers").size());

        // 分层点名核算成功（若串到单层档会完全是另一条路径）
        Map<String, Object> calc = new HashMap<>();
        calc.put("layeredProfileName", sharedName);
        calc.put("widthM", 4.0);
        calc.put("depthM", 1.0);
        ResponseEntity<String> calcResp =
                rest.postForEntity(url("/api/layered-bearings/calculate"), calc, String.class);
        assertEquals(HttpStatus.OK.value(), calcResp.getStatusCode().value());
        assertTrue(json.readTree(calcResp.getBody()).has("equivalentSoil"),
                "分层核算响应必须带折算过程，证明走的是分层路径");
    }

    // ---------- 并发：多份分层剖面 + 多次核算互不污染 ----------

    @Test
    @DisplayName("并发：N 线程各自登记独立分层剖面并多次核算，结果稳定且互不污染")
    void concurrentLayeredCalculationsAreIsolated() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentHashMap<String, Double> firstQu = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String name = "layered-conc-" + UUID.randomUUID() + "-" + idx;
                    // 每层指标随线程不同
                    List<Map<String, Object>> layers = List.of(
                            layer(2, 5 * idx, 5 * idx, 17 + idx),
                            layer(3, 10 * idx, 10 + 5 * idx, 18 + idx));
                    Map<String, Object> reg = new HashMap<>();
                    reg.put("name", name);
                    reg.put("layers", layers);
                    assertEquals(HttpStatus.CREATED.value(),
                            rest.postForEntity(url("/api/layered-profiles"), reg, String.class)
                                    .getStatusCode().value());

                    List<Double> qus = new ArrayList<>();
                    for (int k = 0; k < 5; k++) {
                        Map<String, Object> calc = new HashMap<>();
                        calc.put("layeredProfileName", name);
                        calc.put("widthM", 4.0);
                        calc.put("depthM", 1.0);
                        ResponseEntity<String> r = rest.postForEntity(
                                url("/api/layered-bearings/calculate"), calc, String.class);
                        if (r.getStatusCode() != HttpStatus.OK) {
                            failures.incrementAndGet();
                            return;
                        }
                        qus.add(json.readTree(r.getBody())
                                .get("bearing").get("terms").get("quKpa").asDouble());
                    }
                    double first = qus.get(0);
                    for (double q : qus) {
                        if (Math.abs(q - first) > 1e-7) {
                            failures.incrementAndGet();
                            return;
                        }
                    }
                    firstQu.put(name, first);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务超时");
        pool.shutdown();

        assertEquals(0, failures.get(), "并发分层核算不应有失败或结果漂移");
        assertEquals(threads, firstQu.size());
        // 各线程分层不同，qu 不应写串成全相等（idx=0 时 c=0、φ=0 仍有不同 γ 项参与，qu 也会不同）
        long distinct = firstQu.values().stream().distinct().count();
        assertEquals(threads, distinct, "不同分层剖面的 qu 必须互不相同，结果未被污染");
    }

    private int jsonField(ResponseEntity<String> resp, String field) {
        try {
            return json.readTree(resp.getBody()).get(field).asInt();
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + resp.getBody(), e);
        }
    }
}
