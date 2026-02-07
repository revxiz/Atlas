# Atlas

Kubernetes Orchestration for Minecraft Networks

**Author:** xiz  
**Version:** 1.0  
**Repository:** [github.com/revxiz/Velotim](https://github.com/revxiz/Velotim)

---

## Overview

Atlas is a Kubernetes orchestration plugin for Minecraft networks. It provides intelligent auto-scaling, self-healing infrastructure, and graceful player migration for large-scale server deployments.

Atlas can run as:
- **Velocity Plugin** - Integrated with the Velotim proxy for unified dashboard
- **Standalone Sidecar** - Dedicated orchestration process for maximum scalability

---

## Features

### Auto-Scaling (The Brain)
- **Player Count Scaling** - Scale based on active players per server
- **TPS/MSPT Scaling** - Scale when server performance degrades
- **Queue Depth Scaling** - Scale when connection queue grows
- **Configurable Rules** - Priority-based rule evaluation
- **Cooldown Periods** - Prevent thrashing with scale-up/down cooldowns

### Self-Healing
- **Liveness Probes** - K8s-compatible `/healthz` endpoint
- **Readiness Probes** - K8s-compatible `/readyz` endpoint
- **Circuit Breaker** - Auto-recovery from K8s API failures
- **Pod Event Watching** - Real-time pod state tracking

### Graceful Player Migration
- **Drain Mode** - Stop routing new players to scaling-down servers
- **Configurable Timeout** - Wait for players to leave naturally
- **Force Drain** - Force termination after timeout (optional)
- **State Machine** - ACTIVE -> DRAINING -> DRAINED -> TERMINATED

### Velotim Integration
- **Shared Redis** - Unified state management
- **Dashboard Sync** - Scaling events visible in Velotim dashboard
- **Server Discovery** - Automatic backend registration
- **Soft Dependency** - Works standalone if Velotim not present

### Global Nerve Center Dashboard
- **Level 1: Threat Overview** - Real-time threat level, incidents, throughput
- **Level 2: Infrastructure Map** - Pod grid, scaling controls, canary status
- **Level 3: Intelligence Suite** - Predictions, slow queries, geo heatmap
- **Level 4: Command & Control** - Player search, dangerous actions, audit log

---

## Installation

### As Velocity Plugin

1. Build: `mvn clean package`
2. Copy `target/atlas-1.0.jar` to Velocity `plugins/` folder
3. Start Velocity to generate default config
4. Edit `plugins/atlas/config.yml`
5. Restart Velocity

### As Standalone Sidecar

```bash
# Run Atlas as dedicated orchestrator
java -jar atlas-1.0.jar

# With custom config
java -jar atlas-1.0.jar --config /etc/atlas/config.yml

# With kubeconfig
java -jar atlas-1.0.jar --kubeconfig ~/.kube/config
```

---

## Configuration

See `config.yml` for full configuration options:

```yaml
# Kubernetes API
kubernetes:
  enabled: true
  namespace: "minecraft"
  auth-strategy: "auto"  # auto, in-cluster, kubeconfig

# Scaling Rules
scaling:
  enabled: true
  check-interval-seconds: 10
  cooldown:
    scale-up-seconds: 30
    scale-down-seconds: 300
  rules:
    - name: "lobby-scaling"
      type: "player-count"
      target: "lobby-*"
      scale-up-threshold: 80
      scale-down-threshold: 20

# Player Drain
drain:
  enabled: true
  timeout-seconds: 300
  force-after-timeout: true

# Redis (shared with Velotim)
redis:
  enabled: true
  host: "localhost"
  port: 6379
  password: ""  # ${ATLAS_REDIS_PASSWORD}

# Dashboard
dashboard:
  enabled: true
  port: 9300
  bind: "127.0.0.1"
  auth-token: ""  # Auto-generated
```

---

## Kubernetes Setup

### StatefulSet Example

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lobby
  namespace: minecraft
spec:
  replicas: 2
  selector:
    matchLabels:
      app: minecraft
      component: lobby
  template:
    metadata:
      labels:
        app: minecraft
        component: lobby
        app.kubernetes.io/component: minecraft-server
    spec:
      containers:
        - name: minecraft
          image: your-registry/minecraft-server:latest
          ports:
            - containerPort: 25565
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 20
```

### HPA (Horizontal Pod Autoscaler)

Atlas handles intelligent scaling, but you can add HPA as a safety backstop:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lobby-hpa
  namespace: minecraft
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: StatefulSet
    name: lobby
  minReplicas: 1
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 80
```

---

## Prometheus Metrics

Atlas exposes metrics on the health probe port (`/metrics`):

| Metric | Type | Description |
|--------|------|-------------|
| `atlas_up` | Gauge | Atlas is running |
| `atlas_uptime_seconds` | Gauge | Uptime in seconds |
| `atlas_k8s_circuit_open` | Gauge | K8s circuit breaker state |
| `atlas_k8s_failure_count` | Gauge | K8s API failure count |
| `atlas_scaling_decisions_total` | Counter | Total scaling decisions |
| `atlas_servers_draining` | Gauge | Servers being drained |
| `atlas_redis_connected` | Gauge | Redis connection state |

---

## Environment Detection

Atlas auto-detects hosting environment:

| Mode | Detection | Behavior |
|------|-----------|----------|
| `KUBERNETES` | ServiceAccount token | In-cluster auth |
| `PTERODACTYL` | `P_SERVER_UUID` env | Env var secrets |
| `DOCKER` | `/.dockerenv` file | Bind to 0.0.0.0 |
| `SHARED` | Limited permissions | K8s disabled |
| `STANDALONE` | Default | Full features |

---

## Security

- **Dashboard Auth** - Token-based authentication
- **IP Allowlist** - WAF-style IP filtering
- **Dangerous Actions** - Two-step confirmation tokens
- **Audit Logging** - All actions logged with timestamps

---

## Requirements

- Java 21+
- Velocity 3.5.0+ (for plugin mode)
- Kubernetes 1.24+ (for orchestration)
- Redis 6+ (optional, for state persistence)

---

## License

MIT License - Copyright (c) 2026 xiz
