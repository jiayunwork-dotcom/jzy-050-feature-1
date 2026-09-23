# 浅基础极限承载力核算服务（Bearing Capacity Service）

以**具名「土层参数档」为中心**的岩土浅基础极限承载力核算后端。设计人员反复使用的几套固定
土层指标组合被登记为参数档，持久化在 PostgreSQL 中，可点名取用；也支持在一次核算里临时给出
全套参数。运行时固定 **JDK 17 + PostgreSQL 16**，框架为 **Spring Boot 3.3 / Java 17**。

除单层参数档外，服务另提供一整套并行的**「分层剖面」能力**：按深度顺序登记多土层
（各层自己的层厚、c、φ、γ），核算时自动把基础影响范围内的各层折算成一组等效指标，
再走与单层完全相同的计算内核——折算这一步不再是纸上手算。

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

## 2. 分层剖面与等效折算

现场勘探报告常给出基础影响范围内的多土层剖面（如上覆填土、下伏原状土）。
服务把「各层折成一组等效指标」这一步接了过来，折算完的等效指标与单层参数
**走同一条计算路径**（因子、黏土极限、三项叠加、形状修正全部复用，不重写）。

### 分层剖面模型

一份剖面由若干层按深度顺序拼接，每层给出：**顶面深度 topDepthM、层厚 thicknessM、
黏聚力 c、内摩擦角 φ、重度 γ**。结构约束（登记与核算前都校验，详见第 3 节）：

- 第一层必须贴着地表（顶面深度为 0）；
- 每层顶面必须正好接上一层底面，不许留空、不许重叠；
- 层厚必须为正；每层指标过与单层相同的物理校验。

### 折算窗口

给定基础埋深 Df 与宽度 B，参与折算的深度范围为 **`[Df, Df + B/2]`**：

- 窗口起点是**埋深处**而不是地表——埋深落在第几层内部，就从那一层内部切开算起，
  比埋深更浅的部分不计入；
- 某层只有一部分落进窗口时，只计落在窗口内的那段厚度；
- 窗口下界探到剖面最深处以下时，只用实际能取到的层，不虚构补层；
- 埋深已达/超过剖面底界（窗口内无层可取）时拒绝折算，返回 `LAYERED_PROFILE_TOO_SHALLOW`。

### 等效指标折算规则

| 指标 | 折算方式 |
|---|---|
| 等效黏聚力 c_eq | 按各层参与厚度**加权算术平均** |
| 等效重度 γ_eq | 按各层参与厚度**加权算术平均** |
| 等效内摩擦角 φ_eq | **非线性**：每层 φ 先换算 tan(φ)，按厚度加权平均正切值，再 atan 反算回角度 |

> ⚠️ φ 进入承载力因子公式的方式是非线性的，直接对角度取厚度加权平均会失真，
> 因此必须走「tan 加权 → atan 反算」。同一组分层数据上两种算法结果不同，
> 有自动化测试专门钉住这一点。

**退化**：埋深所在层单独一层就填满整个窗口时，等效指标直接等于该层自身三值，
不掺别的层（也有测试专门钉住）。

核算响应会把折算过程摊开：窗口上下界、每层各自贡献的厚度、折算出的等效
c/φ/γ，连同最终因子与 qu 一并返回，方便人工核对折算这一步。

---

## 3. 模块划分（单一职责，未塞进一个类）

```
domain/        领域模型：土层参数、基础几何、因子、形状系数、三项分项、核算结果
               以及分层模型：土层（SoilLayer）、折算窗口、各层贡献、折算明细
calculation/   BearingFactorsCalculator  因子计算（含度→弧度唯一换算点、φ=0 黏土极限）
               TerzaghiShapeFactorProvider 形状系数
               TerzaghiBearingCalculator  三项叠加（qu）
validation/    InputValidator（计算前物理校验）+ InvalidInputException
profile/       SoilProfileEntity / Repository / SoilProfileService（单层参数档存取）
               DemoProfileInitializer（内置示范档，幂等）
layered/       分层折算三件套 + 编排（与单层内核平行，互不搅和）：
               LayeredProfileValidator    结构校验（贴地表/连续/正层厚/每层物理）
               LayerContributionResolver  折算窗口 [Df, Df+B/2] 与各层贡献厚度
               EquivalentSoilReducer      等效折算（c、γ 算术加权；φ 走 tan 加权反算）
               LayeredBearingService      编排：校验→窗口→折算→复用单层内核
layeredprofile/ 分层剖面实体（剖面 + 层两张表）/ Repository / Service（独立存取）
service/       BearingService（核算编排：点名/临时两种参数来源）
               WidthScanService（固定其余条件、宽度区间扫描）
web/           控制器、DTO、GlobalExceptionHandler（结构化错误）
```

---

## 4. 输入校验（在算承载力因子之前）

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

分层剖面特有的结构校验（全部在进入折算之前拦截，错误说明指出是第几层）：

| 情形 | 原因码 `error` |
|---|---|
| 分层列表为空 / 未给分层也未点名 | `LAYERED_PROFILE_EMPTY` / `LAYERS_MISSING` |
| 第一层顶面深度不为 0（不贴地表） | `FIRST_LAYER_NOT_AT_SURFACE` |
| 层厚 ≤ 0 | `LAYER_THICKNESS_NOT_POSITIVE` |
| 层间留空 | `LAYER_GAP` |
| 层间重叠 | `LAYER_OVERLAP` |
| 层内指标非法（c/φ/γ） | 沿用单层原因码，说明前附层号 |
| 埋深已达/超过剖面底界，窗口内无层可取 | `LAYERED_PROFILE_TOO_SHALLOW` |
| 点名未登记分层剖面 | **404** `LAYERED_PROFILE_NOT_FOUND` |
| 重复登记同名分层剖面 | **409** `LAYERED_PROFILE_ALREADY_EXISTS` |

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

其他：`GET /actuator/health` 健康检查。

### 分层剖面
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/layered-profiles` | 登记分层剖面（name 唯一；登记前做结构 + 每层物理校验） |
| GET  | `/api/layered-profiles` | 列出全部分层剖面（各含原始分层列表） |
| GET  | `/api/layered-profiles/{name}` | 按名取出原始分层列表（未登记 404） |
| POST | `/api/bearings/calculate-layered` | 分层核算（`layeredProfileName` 与 `layers` 二选一） |

登记请求体（按深度顺序给层，第一层顶深必须为 0，层间必须严丝合缝）：
```json
{ "name": "fill-over-native", "description": "填土盖原状土",
  "layers": [
    {"topDepthM": 0, "thicknessM": 2, "cohesionKpa": 10,
     "frictionAngleDeg": 10, "unitWeightKnM3": 17},
    {"topDepthM": 2, "thicknessM": 3, "cohesionKpa": 30,
     "frictionAngleDeg": 30, "unitWeightKnM3": 19} ] }
```

分层核算请求体（点名已登记剖面；或把 `layeredProfileName` 换成 `layers` 数组临时给出）：
```json
{ "layeredProfileName": "fill-over-native", "widthM": 4.0, "depthM": 1.0, "shape": "STRIP" }
```

响应把折算过程摊开（窗口、各层贡献、等效指标）并给出最终结果，例如上面这份剖面
在 B=4、Df=1 下（窗口 [1,3]，两层各占 1m）：
```json
{ "source": "LAYERED_PROFILE", "layeredProfileName": "fill-over-native",
  "window": {"topDepthM": 1.0, "bottomDepthM": 3.0, "totalContributingThicknessM": 2.0},
  "contributions": [
    {"layerIndex": 1, "layerTopDepthM": 0.0, "layerBottomDepthM": 2.0,
     "contributingThicknessM": 1.0},
    {"layerIndex": 2, "layerTopDepthM": 2.0, "layerBottomDepthM": 5.0,
     "contributingThicknessM": 1.0} ],
  "equivalentSoil": {"cohesionKpa": 20.0, "frictionAngleDeg": 20.648,
                     "unitWeightKnM3": 18.0},
  "factors": {"nc": 15.46, "nq": 6.83, "ngamma": 5.90},
  "terms": { ..., "quKpa": 644.5 }, ... }
```
其中等效 φ≈20.65° 来自 tan 加权反算（`atan((tan10°+tan30°)/2)`），
**不是**角度算术平均的 20°；`factors`/`terms` 与单层核算结构完全一致——
等效指标走的就是同一条计算路径（可拿 `equivalentSoil` 去 `/api/bearings/calculate`
核对，qu 精确一致）。

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

## 7. 自动化测试覆盖（共 86 个用例，全绿）

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

分层剖面新增覆盖（与单层路径并列，既有用例不受影响）：

- `LayeredProfileValidatorTest`：第一层不贴地表、**层间留空 / 重叠**（指出第几层）、
  层厚非正、空剖面、层内指标沿用单层判据（含 φ≥90° 拦截）。
- `LayerContributionResolverTest`：窗口 [Df, Df+B/2] 上下界；**窗口起点随埋深下移
  切换**（埋深落在第二层内部时第一层完全不参与）；层界处部分厚度；窗口探出剖面底界
  只取实际层；埋深超过剖面底界报错。
- `EquivalentSoilReducerTest`：**某层单独填满窗口时精确退化为该层自身三值**；
  c、γ 厚度加权算术平均；**φ 走 tan 加权再 atan 反算，且同一组数据上与错误的
  角度算术平均给出不同结果**；全 φ=0 时等效 φ=0（黏土极限仍可达）。
- `LayeredApiIntegrationTest`（真实 HTTP/JSON）：登记/列出/按名取层/点名与临时分层核算、
  折算明细摊开（窗口、各层贡献、等效指标）、等效指标走单层接口 qu 精确一致
  （同一条计算路径）、间隙/重叠/不贴地表/层厚 400 带层号、未登记 404、重复 409、
  剖面太浅 400、退化与截断、**分层剖面与单层参数档同名共存互不干扰**、
  方形修正在分层路径生效；**并发**：8 线程各登记独立剖面并核算互不污染、
  6 线程同名登记恰好一成五冲突。
- `LayeredPersistenceAcrossRestartTest`：分层剖面落关系库，重启第二个 Spring 上下文后
  层序与数值原样取出，并可直接点名核算。
