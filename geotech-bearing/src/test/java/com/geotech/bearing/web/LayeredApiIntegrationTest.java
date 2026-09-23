package com.geotech.bearing.web;

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
 * 分层剖面能力的端到端 HTTP/JSON 集成测试：登记/列出/按名取层、点名与临时分层核算、
 * 折算明细摊开（窗口、各层贡献、等效指标）、结构化错误（间隙/重叠/不贴地表/层厚）、
 * 与单层参数档互不干扰，以及多剖面并发登记 + 核算互不污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LayeredApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---------- 请求体构造 ----------

    private Map<String, Object> layer(double top, double thickness,
                                      double c, double phi, double gamma) {
        Map<String, Object> m = new HashMap<>();
        m.put("topDepthM", top);
        m.put("thicknessM", thickness);
        m.put("cohesionKpa", c);
        m.put("frictionAngleDeg", phi);
        m.put("unitWeightKnM3", gamma);
        return m;
    }

    /** 两层剖面：填土 [0,2) c=10 φ=10 γ=17；原状土 [2,5) c=30 φ=30 γ=19。 */
    private List<Map<String, Object>> fillOverNativeLayers() {
        return List.of(
                layer(0, 2, 10, 10, 17),
                layer(2, 3, 30, 30, 19));
    }

    private Map<String, Object> registerBody(String name, List<Map<String, Object>> layers) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "集成测试剖面");
        body.put("layers", layers);
        return body;
    }

    private String registerFillOverNative() {
        String name = "fill-over-native-" + UUID.randomUUID();
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/layered-profiles"), registerBody(name, fillOverNativeLayers()), String.class);
        assertEquals(HttpStatus.CREATED.value(), resp.getStatusCode().value(),
                "登记分层剖面应 201：" + resp.getBody());
        return name;
    }

    private Map<String, Object> layeredCalcBody(String profileName,
                                                List<Map<String, Object>> layers,
                                                double width, double depth) {
        Map<String, Object> body = new HashMap<>();
        if (profileName != null) {
            body.put("layeredProfileName", profileName);
        }
        if (layers != null) {
            body.put("layers", layers);
        }
        body.put("widthM", width);
        body.put("depthM", depth);
        return body;
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

    // ---------- 登记 / 列出 / 按名取层 / 点名核算 ----------

    @Test
    @DisplayName("登记 -> 列出 -> 按名取原始分层 -> 点名核算 全链路，折算明细摊开")
    void registerListGetAndCalculateByName() throws Exception {
        String name = registerFillOverNative();

        // 按名取出原始分层列表，顺序与数值原样
        ResponseEntity<String> got =
                rest.getForEntity(url("/api/layered-profiles/" + name), String.class);
        assertEquals(HttpStatus.OK.value(), got.getStatusCode().value());
        JsonNode profile = json.readTree(got.getBody());
        JsonNode layers = profile.get("layers");
        assertEquals(2, layers.size());
        assertEquals(0.0, layers.get(0).get("topDepthM").asDouble(), 1e-9);
        assertEquals(2.0, layers.get(0).get("thicknessM").asDouble(), 1e-9);
        assertEquals(10.0, layers.get(0).get("cohesionKpa").asDouble(), 1e-9);
        assertEquals(30.0, layers.get(1).get("cohesionKpa").asDouble(), 1e-9);
        assertEquals(30.0, layers.get(1).get("frictionAngleDeg").asDouble(), 1e-9);

        // 列出全部应包含新登记剖面
        ResponseEntity<String> listed = rest.getForEntity(url("/api/layered-profiles"), String.class);
        assertTrue(listed.getBody().contains(name), "列出结果应包含新登记剖面");

        // 点名核算：B=4, Df=1 -> 窗口 [1,3]，两层各占 1m
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(name, null, 4.0, 1.0), String.class);
        assertEquals(HttpStatus.OK.value(), calc.getStatusCode().value(), calc.getBody());
        JsonNode result = json.readTree(calc.getBody());

        assertEquals("LAYERED_PROFILE", result.get("source").asText());
        assertEquals(name, result.get("layeredProfileName").asText());

        // 折算窗口摊开
        JsonNode window = result.get("window");
        assertEquals(1.0, window.get("topDepthM").asDouble(), 1e-9, "窗口上界=埋深");
        assertEquals(3.0, window.get("bottomDepthM").asDouble(), 1e-9, "窗口下界=埋深+B/2");
        assertEquals(2.0, window.get("totalContributingThicknessM").asDouble(), 1e-9);

        // 各层贡献摊开
        JsonNode contribs = result.get("contributions");
        assertEquals(2, contribs.size());
        assertEquals(1, contribs.get(0).get("layerIndex").asInt());
        assertEquals(1.0, contribs.get(0).get("contributingThicknessM").asDouble(), 1e-9);
        assertEquals(2, contribs.get(1).get("layerIndex").asInt());
        assertEquals(1.0, contribs.get(1).get("contributingThicknessM").asDouble(), 1e-9);

        // 等效指标：c、γ 算术加权；φ 为 tan 加权反算（≈20.648°，绝非算术平均 20°）
        JsonNode eq = result.get("equivalentSoil");
        assertEquals(20.0, eq.get("cohesionKpa").asDouble(), 1e-9);
        assertEquals(18.0, eq.get("unitWeightKnM3").asDouble(), 1e-9);
        assertEquals(20.648, eq.get("frictionAngleDeg").asDouble(), 0.01,
                "等效 φ 应约为 atan((tan10°+tan30°)/2)≈20.65°");
        assertNotEquals(20.0, eq.get("frictionAngleDeg").asDouble(), 0.1,
                "等效 φ 不得是角度算术平均 20°");

        // 手算锚点：qu ≈ 644.46 kPa（φ_eq≈20.65° 的 Nc≈15.46、Nq≈6.83、Nγ≈5.90）
        double qu = result.get("terms").get("quKpa").asDouble();
        assertEquals(644.46, qu, 1.0, "qu 应与手算值一致");

        // 同一条计算路径：拿响应里的等效指标走单层接口，qu 必须精确一致
        Map<String, Object> singleBody = new HashMap<>();
        singleBody.put("soil", Map.of(
                "cohesionKpa", eq.get("cohesionKpa").asDouble(),
                "frictionAngleDeg", eq.get("frictionAngleDeg").asDouble(),
                "unitWeightKnM3", eq.get("unitWeightKnM3").asDouble()));
        singleBody.put("widthM", 4.0);
        singleBody.put("depthM", 1.0);
        ResponseEntity<String> single = rest.postForEntity(
                url("/api/bearings/calculate"), singleBody, String.class);
        assertEquals(HttpStatus.OK.value(), single.getStatusCode().value());
        double singleQu = json.readTree(single.getBody()).get("terms").get("quKpa").asDouble();
        assertEquals(singleQu, qu, 1e-9,
                "分层折算后的等效指标必须与单层参数走同一条计算路径");
    }

    @Test
    @DisplayName("临时给出整组分层数据直接核算，不必先登记")
    void inlineLayersCalculateWithoutRegistration() throws Exception {
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(null, fillOverNativeLayers(), 4.0, 1.0), String.class);
        assertEquals(HttpStatus.OK.value(), calc.getStatusCode().value(), calc.getBody());
        JsonNode result = json.readTree(calc.getBody());
        assertEquals("INLINE_LAYERS", result.get("source").asText());
        assertEquals(20.0, result.get("equivalentSoil").get("cohesionKpa").asDouble(), 1e-9);
        assertEquals(644.46, result.get("terms").get("quKpa").asDouble(), 1.0);
    }

    // ---------- 结构非法：折算之前给出结构化错误并指出第几层 ----------

    @Test
    @DisplayName("层间留空：登记与核算都 400 LAYER_GAP，并指出第 2 层")
    void gapRejectedOnRegisterAndCalculate() throws Exception {
        List<Map<String, Object>> gapped = List.of(
                layer(0, 2, 10, 10, 17),
                layer(2.5, 3, 30, 30, 19));

        ResponseEntity<String> reg = rest.postForEntity(url("/api/layered-profiles"),
                registerBody("gapped-" + UUID.randomUUID(), gapped), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), reg.getStatusCode().value());
        assertReason(reg, "LAYER_GAP");
        assertTrue(json.readTree(reg.getBody()).get("message").asText().contains("第 2 层"));

        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(null, gapped, 4.0, 1.0), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), calc.getStatusCode().value());
        assertReason(calc, "LAYER_GAP");
    }

    @Test
    @DisplayName("层间重叠：400 LAYER_OVERLAP；第一层不贴地表：400 FIRST_LAYER_NOT_AT_SURFACE")
    void overlapAndNonSurfaceFirstLayerRejected() {
        List<Map<String, Object>> overlapped = List.of(
                layer(0, 2, 10, 10, 17),
                layer(1.5, 3, 30, 30, 19));
        ResponseEntity<String> reg = rest.postForEntity(url("/api/layered-profiles"),
                registerBody("overlap-" + UUID.randomUUID(), overlapped), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), reg.getStatusCode().value());
        assertReason(reg, "LAYER_OVERLAP");

        List<Map<String, Object>> floating = List.of(
                layer(0.5, 2, 10, 10, 17),
                layer(2.5, 3, 30, 30, 19));
        ResponseEntity<String> reg2 = rest.postForEntity(url("/api/layered-profiles"),
                registerBody("floating-" + UUID.randomUUID(), floating), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), reg2.getStatusCode().value());
        assertReason(reg2, "FIRST_LAYER_NOT_AT_SURFACE");
    }

    @Test
    @DisplayName("层厚不为正：400 LAYER_THICKNESS_NOT_POSITIVE；层内 φ=90：沿用单层判据拦截")
    void badThicknessAndBadAngleRejected() {
        List<Map<String, Object>> zeroThickness = List.of(
                layer(0, 0, 10, 10, 17));
        ResponseEntity<String> reg = rest.postForEntity(url("/api/layered-profiles"),
                registerBody("thin-" + UUID.randomUUID(), zeroThickness), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), reg.getStatusCode().value());
        assertReason(reg, "LAYER_THICKNESS_NOT_POSITIVE");

        List<Map<String, Object>> badAngle = List.of(
                layer(0, 2, 10, 10, 17),
                layer(2, 3, 30, 90, 19));
        ResponseEntity<String> reg2 = rest.postForEntity(url("/api/layered-profiles"),
                registerBody("bad-angle-" + UUID.randomUUID(), badAngle), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), reg2.getStatusCode().value());
        assertReason(reg2, "FRICTION_ANGLE_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("既未点名也未给分层数组 -> 400 LAYERS_MISSING")
    void missingLayersRejected() {
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(null, null, 4.0, 1.0), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), calc.getStatusCode().value());
        assertReason(calc, "LAYERS_MISSING");
    }

    // ---------- 未登记 / 重复登记 / 剖面太浅 ----------

    @Test
    @DisplayName("点名未登记分层剖面 -> 404 LAYERED_PROFILE_NOT_FOUND")
    void unknownLayeredProfileIsNotFound() {
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody("no-such-layered-" + UUID.randomUUID(), null, 4.0, 1.0), String.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), calc.getStatusCode().value());
        assertReason(calc, "LAYERED_PROFILE_NOT_FOUND");

        ResponseEntity<String> get = rest.getForEntity(
                url("/api/layered-profiles/no-such-layered-" + UUID.randomUUID()), String.class);
        assertEquals(HttpStatus.NOT_FOUND.value(), get.getStatusCode().value());
        assertReason(get, "LAYERED_PROFILE_NOT_FOUND");
    }

    @Test
    @DisplayName("重复登记同名分层剖面 -> 409 LAYERED_PROFILE_ALREADY_EXISTS")
    void duplicateLayeredProfileConflicts() {
        String name = "dup-layered-" + UUID.randomUUID();
        assertEquals(HttpStatus.CREATED.value(), rest.postForEntity(url("/api/layered-profiles"),
                registerBody(name, fillOverNativeLayers()), String.class).getStatusCode().value());
        ResponseEntity<String> again = rest.postForEntity(url("/api/layered-profiles"),
                registerBody(name, fillOverNativeLayers()), String.class);
        assertEquals(HttpStatus.CONFLICT.value(), again.getStatusCode().value());
        assertReason(again, "LAYERED_PROFILE_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("埋深达到剖面底界以下：窗口内无层可取 -> 400 LAYERED_PROFILE_TOO_SHALLOW")
    void depthBeyondProfileBottomRejected() {
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(null, fillOverNativeLayers(), 2.0, 5.0), String.class);
        assertEquals(HttpStatus.BAD_REQUEST.value(), calc.getStatusCode().value());
        assertReason(calc, "LAYERED_PROFILE_TOO_SHALLOW");
    }

    // ---------- 折算行为：退化、截断、多层截断 ----------

    @Test
    @DisplayName("退化：埋深所在层单独填满窗口 -> 等效指标精确等于该层，qu 与该层单层核算一致")
    void singleLayerFillingWindowDegenerates() throws Exception {
        String name = registerFillOverNative();
        // Df=0.5, B=2 -> 窗口 [0.5,1.5] 完全落在第 1 层 [0,2) 内
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(name, null, 2.0, 0.5), String.class);
        assertEquals(HttpStatus.OK.value(), calc.getStatusCode().value(), calc.getBody());
        JsonNode result = json.readTree(calc.getBody());

        assertEquals(1, result.get("contributions").size(), "只有第 1 层参与");
        JsonNode eq = result.get("equivalentSoil");
        assertEquals(10.0, eq.get("cohesionKpa").asDouble(), 0.0, "退化的 c 精确等于该层");
        assertEquals(10.0, eq.get("frictionAngleDeg").asDouble(), 0.0, "退化的 φ 精确等于该层");
        assertEquals(17.0, eq.get("unitWeightKnM3").asDouble(), 0.0, "退化的 γ 精确等于该层");

        // 与直接拿第 1 层指标走单层接口的结果精确一致
        Map<String, Object> singleBody = new HashMap<>();
        singleBody.put("soil", Map.of("cohesionKpa", 10, "frictionAngleDeg", 10, "unitWeightKnM3", 17));
        singleBody.put("widthM", 2.0);
        singleBody.put("depthM", 0.5);
        double singleQu = json.readTree(rest.postForEntity(
                        url("/api/bearings/calculate"), singleBody, String.class).getBody())
                .get("terms").get("quKpa").asDouble();
        assertEquals(singleQu, result.get("terms").get("quKpa").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("窗口探到剖面底界以下：只取实际存在的层，贡献总厚度小于窗口高度")
    void windowTruncatedAtProfileBottom() throws Exception {
        String name = registerFillOverNative();
        // Df=4, B=4 -> 窗口 [4,6]，剖面底界 5：只有第 2 层的 [4,5)=1m 参与 -> 退化为第 2 层
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(name, null, 4.0, 4.0), String.class);
        assertEquals(HttpStatus.OK.value(), calc.getStatusCode().value(), calc.getBody());
        JsonNode result = json.readTree(calc.getBody());

        assertEquals(6.0, result.get("window").get("bottomDepthM").asDouble(), 1e-9,
                "窗口下界仍按 Df+B/2 定义");
        assertEquals(1.0, result.get("window").get("totalContributingThicknessM").asDouble(), 1e-9,
                "剖面不够深时总贡献厚度小于窗口高度，不虚构补层");
        JsonNode contribs = result.get("contributions");
        assertEquals(1, contribs.size());
        assertEquals(2, contribs.get(0).get("layerIndex").asInt());
        assertEquals(1.0, contribs.get(0).get("contributingThicknessM").asDouble(), 1e-9);
        JsonNode eq = result.get("equivalentSoil");
        assertEquals(30.0, eq.get("cohesionKpa").asDouble(), 0.0);
        assertEquals(30.0, eq.get("frictionAngleDeg").asDouble(), 0.0);
        assertEquals(19.0, eq.get("unitWeightKnM3").asDouble(), 0.0);
    }

    @Test
    @DisplayName("多层 + 截断：窗口跨两层且下界探出剖面，各层按实际厚度加权")
    void multiLayerWindowTruncatedAtProfileBottom() throws Exception {
        String name = registerFillOverNative();
        // Df=1.5, B=8 -> 窗口 [1.5,5.5]：第 1 层占 0.5m、第 2 层占 3m（剖面底界 5 截断）
        ResponseEntity<String> calc = rest.postForEntity(url("/api/bearings/calculate-layered"),
                layeredCalcBody(name, null, 8.0, 1.5), String.class);
        assertEquals(HttpStatus.OK.value(), calc.getStatusCode().value(), calc.getBody());
        JsonNode result = json.readTree(calc.getBody());

        assertEquals(3.5, result.get("window").get("totalContributingThicknessM").asDouble(), 1e-9);
        JsonNode eq = result.get("equivalentSoil");
        assertEquals((10.0 * 0.5 + 30.0 * 3.0) / 3.5,
                eq.get("cohesionKpa").asDouble(), 1e-9);
        assertEquals((17.0 * 0.5 + 19.0 * 3.0) / 3.5,
                eq.get("unitWeightKnM3").asDouble(), 1e-9);
        double expectedPhi = Math.toDegrees(Math.atan(
                (0.5 * Math.tan(Math.toRadians(10.0)) + 3.0 * Math.tan(Math.toRadians(30.0))) / 3.5));
        assertEquals(expectedPhi, eq.get("frictionAngleDeg").asDouble(), 1e-9);
    }

    // ---------- 与单层参数档互不干扰 ----------

    @Test
    @DisplayName("分层剖面与单层参数档同名共存：各自存取、各自核算，互不覆盖")
    void layeredAndSingleLayerProfilesCoexistIndependently() throws Exception {
        String shared = "shared-" + UUID.randomUUID();

        // 单层参数档：c=50, φ=0, γ=20
        Map<String, Object> singleReg = new HashMap<>();
        singleReg.put("name", shared);
        singleReg.put("cohesionKpa", 50);
        singleReg.put("frictionAngleDeg", 0);
        singleReg.put("unitWeightKnM3", 20);
        assertEquals(HttpStatus.CREATED.value(), rest.postForEntity(
                url("/api/profiles"), singleReg, String.class).getStatusCode().value());

        // 同名分层剖面：两层
        assertEquals(HttpStatus.CREATED.value(), rest.postForEntity(url("/api/layered-profiles"),
                registerBody(shared, fillOverNativeLayers()), String.class).getStatusCode().value());

        // 单层侧取出的仍是单层数据
        JsonNode singleGot = json.readTree(
                rest.getForEntity(url("/api/profiles/" + shared), String.class).getBody());
        assertEquals(50.0, singleGot.get("cohesionKpa").asDouble(), 1e-9);

        // 分层侧取出的仍是分层数据
        JsonNode layeredGot = json.readTree(
                rest.getForEntity(url("/api/layered-profiles/" + shared), String.class).getBody());
        assertEquals(2, layeredGot.get("layers").size());

        // 同名核算各走各的数据：单层 qu = 50·5.14 + 20·1 = 277
        Map<String, Object> singleCalc = new HashMap<>();
        singleCalc.put("profileName", shared);
        singleCalc.put("widthM", 4.0);
        singleCalc.put("depthM", 1.0);
        JsonNode singleResult = json.readTree(rest.postForEntity(
                url("/api/bearings/calculate"), singleCalc, String.class).getBody());
        assertEquals(50 * 5.14 + 20 * 1.0,
                singleResult.get("terms").get("quKpa").asDouble(), 1e-7);

        // 分层 qu ≈ 644.46（等效指标折算后），与单层结果明显不同
        JsonNode layeredResult = json.readTree(rest.postForEntity(
                url("/api/bearings/calculate-layered"),
                layeredCalcBody(shared, null, 4.0, 1.0), String.class).getBody());
        assertEquals(644.46, layeredResult.get("terms").get("quKpa").asDouble(), 1.0);
    }

    @Test
    @DisplayName("方形形状修正在分层核算同样生效（折算后走同一内核）")
    void squareShapeWorksOnLayeredPath() throws Exception {
        Map<String, Object> body = layeredCalcBody(null, fillOverNativeLayers(), 4.0, 1.0);
        body.put("shape", "SQUARE");
        JsonNode square = json.readTree(rest.postForEntity(
                url("/api/bearings/calculate-layered"), body, String.class).getBody());
        assertEquals(1.3, square.get("shapeFactors").get("sc").asDouble(), 1e-9);
        assertEquals(0.8, square.get("shapeFactors").get("sGamma").asDouble(), 1e-9);

        body.put("shape", "STRIP");
        JsonNode strip = json.readTree(rest.postForEntity(
                url("/api/bearings/calculate-layered"), body, String.class).getBody());
        assertNotEquals(strip.get("terms").get("quKpa").asDouble(),
                square.get("terms").get("quKpa").asDouble(), 1e-6);
    }

    // ---------- 并发：多剖面登记 + 核算互不污染 ----------

    @Test
    @DisplayName("并发：N 个线程各自登记独立分层剖面并核算，结果互不污染")
    void concurrentLayeredRegistrationsAndCalculationsAreIsolated() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentHashMap<String, Double> expectedQu = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String name = "conc-layered-" + UUID.randomUUID() + "-" + idx;
                    List<Map<String, Object>> layers = List.of(
                            layer(0, 2, 5 + idx, 5 + idx, 17 + idx),
                            layer(2, 3, 25 + idx, 20 + idx, 18 + idx));
                    assertEquals(HttpStatus.CREATED.value(),
                            rest.postForEntity(url("/api/layered-profiles"),
                                            registerBody(name, layers), String.class)
                                    .getStatusCode().value());

                    // 窗口 [0.5, 2.5] 跨两层，折算真正发生；同名多次核算结果必须稳定
                    List<Double> qus = new ArrayList<>();
                    for (int k = 0; k < 5; k++) {
                        ResponseEntity<String> r = rest.postForEntity(
                                url("/api/bearings/calculate-layered"),
                                layeredCalcBody(name, null, 4.0, 0.5), String.class);
                        if (r.getStatusCode() != HttpStatus.OK) {
                            failures.incrementAndGet();
                            return;
                        }
                        qus.add(json.readTree(r.getBody()).get("terms").get("quKpa").asDouble());
                    }
                    double first = qus.get(0);
                    for (double q : qus) {
                        if (Math.abs(q - first) > 1e-7) {
                            failures.incrementAndGet();
                            return;
                        }
                    }
                    expectedQu.put(name, first);
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
        assertEquals(threads, expectedQu.size());
        long distinct = expectedQu.values().stream().distinct().count();
        assertEquals(threads, distinct, "不同剖面的 qu 必须互不相同，结果未被污染");
    }

    @Test
    @DisplayName("并发登记同一剖面名：恰好一个成功，其余全部 409")
    void concurrentDuplicateLayeredRegistrationSingleWinner() throws Exception {
        int threads = 6;
        String name = "race-layered-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int code = rest.postForEntity(url("/api/layered-profiles"),
                                    registerBody(name, fillOverNativeLayers()), String.class)
                            .getStatusCode().value();
                    if (code == HttpStatus.CREATED.value()) {
                        created.incrementAndGet();
                    } else if (code == HttpStatus.CONFLICT.value()) {
                        conflicts.incrementAndGet();
                    } else {
                        failures.add("线程得到非预期状态码：" + code);
                    }
                } catch (Exception ex) {
                    failures.add("线程得到非预期异常：" + ex);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(failures.isEmpty(), "不应有非预期结果：" + failures);
        assertEquals(1, created.get(), "同名并发登记只能有一笔成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须冲突 409");
    }
}
