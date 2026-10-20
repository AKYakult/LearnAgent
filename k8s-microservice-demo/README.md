# 🚀 K8s 微服务全链路实战 Demo

> 两个 Spring Boot 微服务 → Docker 容器化 → K8s 集群编排 → 负载均衡 + 自愈
> 技术栈：**JDK 21 · Spring Boot 4.1.1 · Gradle · Docker · Kubernetes**
> 不依赖 Spring Cloud —— 因为 K8s 本身就是注册中心 + 负载均衡器

---

## 📁 项目结构

```
k8s-microservice-demo/
├── settings.gradle.kts          # 多模块根配置
├── README.md                     # ← 你正在看的这份指南
│
├── product-service/              # 📦 商品服务（被调用方）
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/
│       ├── java/jin/product/
│       │   ├── ProductApplication.java     # 启动类
│       │   └── controller/ProductController.java  # 商品接口
│       └── resources/application.yml
│
├── order-service/                # 📦 订单服务（调用方）
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/
│       ├── java/jin/order/
│       │   ├── OrderApplication.java       # 启动类 + RestClient Bean
│       │   ├── client/ProductClient.java    # 远程调用商品服务
│       │   └── controller/OrderController.java # 下单接口
│       └── resources/application.yml
│
└── k8s/
    └── k8s-deploy.yaml           # K8s 编排文件（Deployment + Service）
```

---

## 🧠 核心概念：为什么不需要 Spring Cloud？

| 能力 | 传统 Spring Cloud | 本 Demo 的 K8s 原生方案 |
|------|-------------------|--------------------------|
| 服务发现 | Eureka/Nacos 注册中心 | K8s **Service + 内置 DNS** |
| 负载均衡 | Ribbon / LoadBalancer | K8s Service **自动轮询** Pod |
| 远程调用 | OpenFeign 声明式客户端 | `RestClient`（Spring 6.1+ 自带） |
| 配置管理 | Spring Cloud Config | K8s ConfigMap / Secret |
| 健康检查 | Spring Boot Actuator | K8s liveness/readiness Probe |

代码里 `ProductClient` 写的是：
```java
uri("http://product-service:8081/product/" + id)
//           ^^^^^^^^^^^^^^^^
//           这不是 IP！是 K8s Service 的名字
//           K8s DNS 会自动解析 → ClusterIP → 负载均衡到多个 Pod
```

**一句话理解**：K8s Service 就是一个"虚拟 IP + DNS 名 + 负载均衡器"三合一，
Spring Cloud 做的事情 K8s 在基础设施层全包了。

---

## 📝 5 步实操指南

### 第 1 步：本地验证环境

```bash
docker -v                    # Docker 引擎
kubectl get nodes            # K8s 集群（Docker Desktop 设置里勾选 Enable Kubernetes）
java -version                # JDK 21
```

> ✅ 验证标准：`kubectl get nodes` 输出中节点状态为 `Ready`

---

### 第 2 步：编译打包两个微服务

在 demo 根目录执行：

```bash
# Windows
gradlew.bat :product-service:bootJar :order-service:bootJar

# Mac / Linux
./gradlew :product-service:bootJar :order-service:bootJar
```

> ✅ 验证标准：`product-service/build/libs/*.jar` 和 `order-service/build/libs/*.jar` 生成成功

---

### 第 3 步：构建 Docker 镜像

```bash
cd product-service
docker build -t product-service:latest .

cd ../order-service
docker build -t order-service:latest .
```

> ✅ 验证标准：`docker images` 能看到两个 `latest` 镜像

---

### 第 4 步：一键部署到 K8s

```bash
cd ../
kubectl apply -f k8s/k8s-deploy.yaml
```

查看部署状态：
```bash
kubectl get pods    # 应看到 2 个 product + 1 个 order，全部 Running
kubectl get svc     # 应看到 product-service 和 order-service
```

> ✅ 验证标准：3 个 Pod 状态全部 `Running`

---

### 第 5 步：三大实验感受 K8s 超能力

#### 实验 A：分布式调用 + 负载均衡

```bash
# 连续访问 5 次，观察返回的 pod 名字在两个 product Pod 之间交替
for i in 1 2 3 4 5; do curl -s http://localhost:30080/order/create/999 | python -m json.tool; done
```

你会看到类似这样的输出，`productInfo.data.pod` 的值在两个 Pod 名之间交替：
```json
{
  "msg": "订单创建成功",
  "orderPod": "order-deployment-xxx",
  "productInfo": {
    "code": 200,
    "data": { "pod": "product-deployment-aaa", ... }   ← 注意这里会变
  }
}
```

#### 实验 B：一键弹性扩容

```bash
# 把商品服务从 2 个 Pod 扩容到 5 个
kubectl scale deployment product-deployment --replicas=5

# 立即查看
kubectl get pods
```

再次刷新浏览器，新 Pod 瞬间加入负载均衡，**无需改代码、无需重启订单服务**。

#### 实验 C：故障自愈

```bash
# 故意删掉一个商品 Pod（模拟服务器宕机）
kubectl delete pod <复制一个 product Pod 的名字>

# 立刻查看
kubectl get pods
```

K8s 会在 1 秒内自动拉起一个新 Pod 替补，前端下单完全不受影响。

---

## 🌐 请求全链路图

```
浏览器 http://localhost:30080/order/create/999
    │
    ▼
┌─────────────────────────────────────┐
│  K8s NodePort Service (order)       │  ← 端口 30080 → 8080
│  把外部流量导入集群                   │
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  order Pod (1个副本)                 │
│  OrderController.createOrder()       │
│      ↓ RestClient.get()              │
│      ↓ http://product-service:8081   │  ← K8s DNS 解析这个名字
└──────────┬──────────────────────────┘
           ▼
┌─────────────────────────────────────┐
│  K8s Service (product-service)      │  ← ClusterIP + 负载均衡
│  自动轮询分发到后端多个 Pod            │
└──────┬──────────┬───────────────────┘
       ▼          ▼
   ┌────────┐  ┌────────┐
   │product │  │product │   ← 2个(可扩容到N个)商品Pod
   │Pod #1  │  │Pod #2  │
   └────────┘  └────────┘
```

---

## 📖 关键代码导读

### 1. ProductController —— 为什么返回 Pod 名？

```java
String pod = InetAddress.getLocalHost().getHostName();
```
在 K8s 里，每个 Pod 的 hostname 就是它的 Pod 名（如 `product-deployment-7b8f9-xyz12`）。
把它放进返回值，就能在浏览器里直观看到"这次请求被分到了哪个 Pod"。

### 2. ProductClient —— 为什么不需要 IP？

```java
restClient.get()
    .uri("http://product-service:8081/product/" + id)
```
`product-service` 是 K8s Service 的 metadata.name。
K8s 内置 DNS（CoreDNS）会把它解析成 Service 的 ClusterIP，
Service 再用 kube-proxy（iptables/IPVS 规则）做轮询负载均衡。

### 3. k8s-deploy.yaml —— Deployment vs Service

| 对象 | 职责 | 类比 |
|------|------|------|
| **Deployment** | 管理 Pod 副本数、滚动更新、故障自愈 | "车间主任"——保证始终有 N 个工人在岗 |
| **Service** | 固定 DNS 名 + 虚拟 IP + 负载均衡 | "前台总机"——不管哪个工人接电话，对外号码不变 |

---

## ➕ 进阶：跑通后的下一步

1. **加网关**：前面放一个 Spring Cloud Gateway / Nginx Ingress，统一入口
2. **加中间件**：在 K8s 里部署 Redis、MySQL（用 Helm 一键安装）
3. **配置分离**：把端口等配置提取到 K8s ConfigMap，不重新打包就能改配置
4. **健康检查**：给 Deployment 加 livenessProbe / readinessProbe，K8s 自动踢出不健康的 Pod
5. **滚动发布**：改代码 → 重新 build 镜像 → `kubectl set image`，K8s 零停机更新

---

## ⚡ 快速排错

| 症状 | 原因 | 解决 |
|------|------|------|
| Pod 状态 `ImagePullBackOff` | 镜像名不对或没打 `Never` | 确认 `imagePullPolicy: Never`，确认 `docker images` 有镜像 |
| Pod 状态 `CrashLoopBackOff` | 应用启动失败 | `kubectl logs <pod-name>` 看日志 |
| `curl localhost:30080` 不通 | order-service 没起来或端口不对 | `kubectl get svc` 确认 NodePort 30080 |
| 返回 502/503 | product-service 不可达 | `kubectl exec` 进 order Pod 执行 `nslookup product-service` |
