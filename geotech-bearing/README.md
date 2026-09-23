# 浅基础极限承载力核算服务（Bearing Capacity Service）

以**具名「土层参数档」为中心**的岩土浅基础极限承载力核算后端。设计人员反复使用的几套固定
土层指标组合被登记为参数档，持久化在 PostgreSQL 中，可点名取用；也支持在一次核算里临时给出
全套参数。运行时固定 **JDK 17 + PostgreSQL 16**，框架为 **Spring Boot 3.3 / Java 17**。

---

## 1. 计算内核

### 太沙基条形基础公式

```
qu = c·Nc·sc + γ·Df·Nq·sq + 0.5·γ·B·Nγ·sγ
```

| 符号 | 含义 | 单位 |
|---|---|---|
| c  | 土的黏聚力 | kPa |
| φ  | 内摩擦角 | 度（°），合法区间 [0, 90) |
| γ  | 土的重度 | kN/m³ |
| B  | 条形基础宽度（方形为边长、圆形为直径） | m |
| Df | 基础埋深 | m |
| sc / sq / sγ | 三项各自的形状系数（条形全为 1） | — |

三项依次为**黏聚力项**、**超载（埋深）项**、**自重（宽度）项**。

### 承载力因子（经典闭合式）

- `Nq = e^(π·tanφ) · tan²(45° + φ/2)`
- `Nc = (Nq − 1) · cotφ`
- `Nγ = 2·(Nq + 1)·tanφ` —— **采用 Meyerhof（1963）表达式**。

> ⚠️ **关于 Nγ 的说明（务必知悉）**：太沙基体系下 Nγ 没有公认的唯一闭式，各教材/表格取值
> 分散（Terzaghi 原表、Meyerhof、Vesic 等差异很大，φ=30° 时从约 16.8 到 22+ 不等）。
> 本服务**固定采用 Meyerhof 式 `2·(Nq+1)·tanφ`** 并在此明确写明，以保证结果确定、可复现。
> φ=30° 时：`Nc≈30.14，Nq≈18.40，Nγ≈22.40`（Nc、Nq 与经典文献表值一致）。

### 黏土极限（φ = 0 退化分支）

`cot(0)` 会除零。当 φ=0（实现中按极小容差判定）时**切换到黏土极限**，直接取：

```
Nc = 5.14（即 π+2 的经典设计取值），Nq = 1，Nγ = 0
```

此时只剩黏聚力项与超载项，自重项恒为 0，绝不让 `cot(0)` 把计算炸掉。

### 角度单位一致性

角度「度 → 弧度」的换算**只发生在 `BearingFactorsCalculator` 一处**（`Math.toRadians`），
Nq、Nc、Nγ 与下游三项叠加共用同一组换算结果，不会出现因子用度、叠加用弧度对不上的问题。

### 形状系数（可选，缺省条形）

| 形状 | sc（黏聚力项） | sq（超载项） | sγ（自重项） |
|---|---|---|---|
| 条形 STRIP（默认） | 1.0 | 1.0 | 1.0 |
| 方形 SQUARE | 1.3 | 1.0 | 0.8 |
| 圆形 CIRCULAR | 1.3 | 1.0 | 0.6 |

三项**各自独立修正、三项都乘**，不存在只修一项的情况。

---

## 1A. 分层剖面：折算窗口与非线性等效指标

当基础影响范围内摆着多层不同的土（例如上面填土、下面原状土）时，登记侧按深度顺序
给出每一层的**层厚、c、φ、γ**；核算前服务在给定埋深 Df 与宽度 B 下，先把落在折算
窗口内的各层折成一组等效单层指标，再让这组等效指标走**与单层参数完全相同的**因子/
三项叠加计算路径。因子计算、黏土极限退化、形状修正等内核一行未改，分层折算只是喂给
内核之前多做的一步预处理。

### 折算窗口（起点是埋深，不是地表）

```
窗口 = [Df, Df + B/2]
```

- 窗口从**埋深处**起算；埋深落在哪一层内部，窗口就从那一层往下切开，比 Df 更浅的部分
  不计入折算；
- 各层按其在窗口内**实际占到的厚度**参与折算；只有一部分落入窗口的层只算落入的那段，
  窗口以下的层完全不参与；
- 窗口下界探出剖面底面时**不补虚拟层**，实际能取到多少算多少，响应中以
  `windowTruncated` 标明；
- 埋深已达到/超过剖面底面、窗口内没有任何层可取时，直接返回结构化错误
  `EMBEDMENT_BELOW_PROFILE`。

### 等效指标折算（φ 必须非线性折算）

设第 i 层在窗口内的实际厚度为 tᵢ：

```
c_eq  = Σ(tᵢ·cᵢ) / Σtᵢ                  （黏聚力：厚度加权算术平均）
γ_eq  = Σ(tᵢ·γᵢ) / Σtᵢ                  （重度：厚度加权算术平均）
φ_eq  = atan( Σ(tᵢ·tan φᵢ) / Σtᵢ )      （内摩擦角：先换 tan 再厚度加权，最后反算角度）
```

> ⚠️ **φ 绝不允许直接对角度做厚度加权算术平均**。角度是非线性量，直接平均后代入承载力
> 因子公式会失真；必须先换算成正切再加权，再 `atan` 反算回角度（各层 φ∈[0,90)，
> 加权正切非负，反算结果天然落在 [0,90)）。自动化测试在同一组分层上同时计算两种口径，
> 钉死它们必须给出不同结果。

退化：如果窗口内只有一层参与（埋深处所在层单独就填满整个窗口，或窗口被剖面底面截断到
只剩一层），等效结果**严格等于这一层自己的 c、φ、γ**（直接返回该层指标，不经三角
往返，连浮点噪声都不留），该退化点有专门测试钉住。

### 分层结构校验（折算前拦截，错误指出层号）

- 至少一层；**第一层必须从深度 0 起算**（贴地表）；
- 每层**层厚必须为正**；
- 自上而下每层顶面必须与上一层底面**正好相接**：有间隙 → `LAYER_GAP`，
  有重叠 → `LAYER_OVERLAP`（1e-9 m 内的接续面浮点噪声视为正好相接）；
- 每层各自的 c、φ、γ 沿用既有判据（`COHESION_NEGATIVE`、
  **`FRICTION_ANGLE_OUT_OF_RANGE`（φ≥90° 照样拦）**、`UNIT_WEIGHT_NOT_POSITIVE`），
  错误响应额外携带 `layerIndex`（0 基，与数组下标一致），明确是第几层出了问题，
  绝不静默跳过继续折算。

### 独立性

分层剖面与单层参数档是**两套并行独立的能力**：独立的表
（`layered_soil_profiles` / `soil_layers`）、独立的接口路径、独立的命名空间——
两边允许同名共存，点名核算时一次请求明确分辨自己引用的是哪一种，互不覆盖、互不干扰。
分层剖面同样持久化在 PostgreSQL，重启后仍可点名取出。

---

## 2. 模块划分（单一职责，未塞进一个类）

```
domain/        领域模型：土层参数、基础几何、因子、形状系数、三项分项、核算结果
calculation/   BearingFactorsCalculator  因子计算（含度→弧度唯一换算点、φ=0 黏土极限）
               TerzaghiShapeFactorProvider 形状系数
               TerzaghiBearingCalculator  三项叠加（qu）
validation/    InputValidator（计算前物理校验）+ InvalidInputException
profile/       SoilProfileEntity / Repository / SoilProfileService（参数档存取）
               DemoProfileInitializer（内置示范档，幂等）
service/       BearingService（核算编排：点名/临时两种参数来源）
               WidthScanService（固定其余条件、宽度区间扫描）
layered/       并行的分层剖面能力（独立子包，不与单层内核搅在一起）：
  domain/        SoilLayer（绝对深度区间）、LayeredProfileAssembler（层厚累加为深度区间）、
                 ReductionWindow、LayerContribution
  validation/    LayeredProfileValidator（贴地表/相接/层厚/各层指标，错误带层号）
  calc/          ReductionWindowResolver（折算窗口 [Df, Df+B/2] 确定与切层）
                 EquivalentParametersReducer（c/γ 厚度加权；φ 走 tan 加权反算）
                 LayeredBearingResult（摊开折算过程的结果）
  profile/       LayeredProfileEntity / SoilLayerEntity / Repository /
                 LayeredProfileService（分层剖面独立存取）
  service/       LayeredBearingService（分层核算编排：折算后复用既有 TerzaghiBearingCalculator）
web/           控制器、DTO、GlobalExceptionHandler（结构化错误；分层错误额外带 layerIndex）
```

---

## 3. 输入校验（在算承载力因子之前）

下列取值一律返回 **HTTP 400 + 结构化错误原因码**，绝不进入因子计算：

| 情形 | 原因码 `error` |
|---|---|
| 宽度 ≤ 0 | `WIDTH_NOT_POSITIVE` |
| 重度 ≤ 0 | `UNIT_WEIGHT_NOT_POSITIVE` |
| 埋深 < 0 | `DEPTH_NEGATIVE` |
| 黏聚力 < 0 | `COHESION_NEGATIVE` |
| φ < 0 | `FRICTION_ANGLE_NEGATIVE` |
| **φ ≥ 90°**（防止 tan(45+45) 发散） | `FRICTION_ANGLE_OUT_OF_RANGE` |
| 点名未登记档 | **404** `PROFILE_NOT_FOUND`（不拿默认值凑结果） |
| 重复登记同名档 | **409** `PROFILE_ALREADY_EXISTS` |

错误响应统一形如：
```json
{ "timestamp": "...", "status": 400, "error": "WIDTH_NOT_POSITIVE",
  "message": "基础宽度必须为正：B=-1.0 m。", "path": "/api/bearings/calculate" }
```

---

## 4. HTTP 接口

Base URL：`http://localhost:8080`

### 参数档
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/profiles` | 登记参数档（name 唯一；登记前同样做物理校验） |
| GET  | `/api/profiles` | 列出全部参数档 |
| GET  | `/api/profiles/{name}` | 按名查询（未登记 404） |

登记请求体：
```json
{ "name": "soft-clay", "cohesionKpa": 20, "frictionAngleDeg": 0,
  "unitWeightKnM3": 17.5, "description": "软黏土" }
```

### 承载力
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/bearings/calculate` | 一次核算（`profileName` 与 `soil` 二选一） |
| GET  | `/api/bearings/factors?frictionAngleDeg=30` | 仅查三个因子 |
| POST | `/api/bearings/scan` | 宽度区间扫描，返回 qu 随宽度点列 |

核算请求体（临时参数）：
```json
{ "soil": {"cohesionKpa": 30, "frictionAngleDeg": 0, "unitWeightKnM3": 18},
  "widthM": 2.0, "depthM": 1.5, "shape": "STRIP" }
```
核算请求体（点名已登记档）：
```json
{ "profileName": "medium-dense-sand", "widthM": 3.0, "depthM": 1.5, "shape": "SQUARE" }
```
`shape` 可省略（缺省 `STRIP`），可选 `SQUARE` / `CIRCULAR`。

返回包含三因子、三项形状系数、三项分项（kPa）与 `quKpa`，例如 φ=0 黏土：
```json
{ "factors": {"nc":5.14,"nq":1.0,"ngamma":0.0},
  "terms": {"cohesionTermKpa":154.2,"surchargeTermKpa":27.0,
            "weightTermKpa":0.0,"quKpa":181.2}, ... }
```

扫描请求体：
```json
{ "profileName": "medium-dense-sand", "depthM": 1.0,
  "minWidthM": 1.0, "maxWidthM": 4.0, "stepM": 1.0 }
```
返回 `{ "count": 4, "points": [ …每个宽度一个完整核算结果… ] }`。

### 分层剖面

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/layered-profiles` | 登记分层剖面（name 在分层表内唯一；登记前做结构与指标校验） |
| GET  | `/api/layered-profiles` | 列出全部分层剖面 |
| GET  | `/api/layered-profiles/{name}` | 按名取出原始分层列表（层序、层厚、各层指标） |
| POST | `/api/layered-bearings/calculate` | 分层核算（`layeredProfileName` 与 `layers` 二选一） |

登记请求体（每层给**层厚**与三个指标，层序即数组顺序、自上而下）：
```json
{ "name": "fill-over-alluvium", "description": "填土下卧原状土",
  "layers": [
    {"thicknessM": 2.0, "cohesionKpa": 10, "frictionAngleDeg": 10, "unitWeightKnM3": 18},
    {"thicknessM": 3.0, "cohesionKpa": 30, "frictionAngleDeg": 30, "unitWeightKnM3": 20},
    {"thicknessM": 5.0, "cohesionKpa": 5,  "frictionAngleDeg": 25, "unitWeightKnM3": 19}
  ] }
```

分层核算请求体（临时给整组分层不必先登记；点名则给 `layeredProfileName`）：
```json
{ "layers": [ ...同上... ], "widthM": 4.0, "depthM": 1.0, "shape": "STRIP" }
```

响应除最终因子与 qu 外，把折算过程完全摊开：
```json
{ "source": "INLINE", "profileName": null,
  "window": {"topDepthM": 1.0, "bottomDepthM": 3.0, "lengthM": 2.0},
  "windowTruncated": false, "actualWindowThicknessM": 2.0,
  "layerContributions": [
    {"layerIndex": 0, "topDepthM": 0.0, "bottomDepthM": 2.0, "thicknessM": 2.0,
     "cohesionKpa": 10.0, "frictionAngleDeg": 10.0, "unitWeightKnM3": 18.0,
     "contributedThicknessM": 1.0, "participating": true},
    {"layerIndex": 1, "...": "...", "contributedThicknessM": 1.0, "participating": true},
    {"layerIndex": 2, "...": "...", "contributedThicknessM": 0.0, "participating": false}
  ],
  "equivalentSoil": {"cohesionKpa": 20.0, "frictionAngleDeg": 20.548..., "unitWeightKnM3": 19.0},
  "bearing": { "source": "EQUIVALENT",
    "factors": {"nc": ..., "nq": ..., "ngamma": ...},
    "shapeFactors": {"sc": 1.0, "sq": 1.0, "sGamma": 1.0},
    "terms": {"cohesionTermKpa": ..., "surchargeTermKpa": ..., "weightTermKpa": ..., "quKpa": ...} } }
```

分层结构错误仍是统一结构化错误（400），并额外携带出问题的层号：
```json
{ "status": 400, "error": "LAYER_GAP",
  "message": "第 2 层顶面（深度 3.0 m）没有接住上一层底面（深度 2.0 m），中间留有 1.0 m 空隙，不允许。",
  "layerIndex": 1, "path": "/api/layered-bearings/calculate" }
```

其他：`GET /actuator/health` 健康检查。

### 内置示范参数档

随服务内置 **`medium-dense-sand`（中密砂土：φ=30°, c=0, γ=19 kN/m³）**。
因子接近文献表值，且因 c=0，第一项消失，**qu 随宽度增大而增大**，便于手工核对。

---

## 5. 一步构建并拉起（Docker）

前置：安装 Docker（含 Compose v2）。

```bash
cd geotech-bearing
docker compose up --build          # 前台；或 ./run.sh 后台并等待就绪
```

这会：

1. 用 `maven:3.9-eclipse-temurin-17` 多阶段构建打包（固定 JDK 17）；
2. 启动 **`postgres:16`** 数据库（数据持久化到命名卷 `pgdata`，健康检查通过后再起 API）；
3. 启动 API 容器（`eclipse-temurin:17-jre`，非 root 运行），对外 `http://localhost:8080`。

停止与清理：

```bash
docker compose down          # 停止；保留数据卷
docker compose down -v       # 停止并删除数据库卷
```

---

## 6. 本地开发 / 测试（无需 Docker 与外部数据库）

需要 JDK 17。仓库自带 Maven Wrapper：

```bash
./mvnw test          # 全部自动化测试（用 H2 的 PostgreSQL 兼容模式，无需外部 DB）
./mvnw package       # 打包到 target/bearing-capacity-service-1.0.0.jar
```

连本地/容器内 PostgreSQL 16 直接运行：

```bash
java -jar target/bearing-capacity-service-1.0.0.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/geotech \
  --spring.datasource.username=geotech --spring.datasource.password=geotech
```

---

## 7. 自动化测试覆盖（共 89 个用例，全绿）

- `BearingFactorsCalculatorTest`：φ=0 黏土极限（5.14/1/0、不除零、全有限值）；
  φ=30° 接近文献表值；因子随 φ 单调上升；极小 φ 数值稳定。
- `TerzaghiBearingCalculatorTest`（**逐条锁定联动关系**）：
  - φ=0 且 c>0：只剩黏聚力项与超载项，自重项为 0；
  - c=0：第一项消失，qu 随宽度与埋深变化；
  - **单独宽度加倍 → 自重项精确加倍**，其余两项不变；
  - **单独埋深加倍 → 超载项精确加倍**，其余两项不变；
  - **单独提高 φ → Nq 上升、qu 上升**；
  - 方形/圆形：三项都按各自形状系数修正（不许只修一项）。
- `InputValidatorTest`：宽度/重度非正、埋深/φ 为负、**φ≥90° 拦截**、扫描区间、未知形状等。
- `BearingApiIntegrationTest`（真实 HTTP/JSON + Spring 容器）：登记/列出/查询/点名/未登记 404、
  重复 409、φ=90 拦截、示范档扫描趋势、方形修正；
  **并发**：8 线程各登记独立档并多次核算结果稳定且互不污染；6 线程并发登记同名恰好一成五冲突。
- `PersistenceAcrossRestartTest`：参数档落关系库，关闭并重启第二个 Spring 上下文后仍可点名取用。
- `LayeredProfileValidatorTest`（分层结构）：第一层不贴地表、**层间间隙 LAYER_GAP**、
  **重叠 LAYER_OVERLAP**、层厚非正、层数为空、某层 φ=90°/重度非正/黏聚力为负（沿用单层原因码
  且带层号）、接续面 1e-12 浮点噪声放行。
- `ReductionWindowResolverTest`（折算窗口）：**窗口起点随埋深下移并从埋深处所在层切开**、
  部分落入只计落入段、跨三层窗口、**窗口探出剖面底面截断且不补层**、埋深正好落在层界面、
  埋深超过剖面底面拒绝、0 贡献层不参与。
- `EquivalentParametersReducerTest`（**非线性折算**）：c/γ 厚度加权算术平均；
  **φ 走 tan 加权再 atan 反算，与错误的角度算术平均在同一组数据上必须给出不同结果**
  （等厚与不等厚两组）；**单层填满窗口时严格退化为该层自身 c/φ/γ（含 φ 精确相等）**；
  全 φ=0 退化；无参与层拒绝。
- `LayeredBearingServiceTest`：临时分层核算的窗口/等效值/qu、退化情形与单层路径结果一致、
  截断窗口、层间间隙在折算前拦截、埋深越界、方形修正沿用既有内核、宽度非正拦截。
- `LayeredBearingApiIntegrationTest`（分层 HTTP 端到端）：登记/列出/按名取出、重名 409、
  未登记 404、层厚非正/某层 φ=90/缺字段全部 400 且带 `layerIndex`、埋深越界、
  点名与临时来源二选一、折算过程完整摊开、退化 qu 与既有单层核算接口结果严格一致、
  截断标记、埋深下移切换窗口起点；**分层剖面与单层档同名共存、互不串台**；
  **8 线程并发各登记独立分层剖面并多次核算，结果稳定、互不污染**。
- `LayeredProfilePersistenceTest`：分层剖面（含各层）落关系库，重启第二个上下文后
  层序、层厚、各层指标与累加出的顶/底深度全部不丢。
