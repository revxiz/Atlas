# Atlas

Production-grade Kubernetes orchestration for large Minecraft networks. Atlas provides intelligent auto-scaling, graceful player migration, and operational telemetry designed for availability, performance, and security.

Author: xiz  
Version: 1.0  
Repository: [github.com/revxiz/Atlas](https://github.com/revxiz/Atlas)  
Part of the Velotim Suite: [github.com/revxiz/Velotim](https://github.com/revxiz/Velotim)

---

## Overview

Atlas is built for networks that need more than simple CPU-based autoscaling. It understands game-level signals (player count, TPS, MSPT, queue depth) and makes safe, SRE-minded scaling decisions. Atlas can run as a Velocity plugin or standalone sidecar, integrating with Velotim for a unified operations dashboard.

### Key Benefits

- **Game-Aware Scaling** - Scale based on Minecraft metrics, not just CPU/RAM
- **Zero-Disruption Operations** - Graceful player migration during scale-down
- **SRE-First Design** - Built-in circuit breakers, audit trails, and incident response
- **Velotim Integration** - Unified dashboard for network-wide visibility

---

## Features

### Intelligent Auto-Scaling

| Feature | Description |
|---------|-------------|
| Game-Aware Metrics | Scale based on player count, TPS, MSPT, not just CPU/Memory |
| Custom Scaling Rules | Define complex conditions with cooldowns and thresholds |
| Predictive Scaling | Forecast capacity needs based on historical patterns |
| Min/Max Replicas | Enforce boundaries to control costs and availability |
| Multi-Metric Rules | Combine multiple conditions (AND/OR logic) |

### Graceful Player Migration

| Feature | Description |
|---------|-------------|
| Drain Mode | Mark servers for termination without kicking players |
| Configurable Timeout | Wait for players to leave naturally (default: 5 minutes) |
| Zero-Disruption Scale-Down | Players seamlessly redirected to healthy servers |
| Force After Timeout | Optional forced migration after drain timeout |

### Kubernetes Integration

| Feature | Description |
|---------|-------------|
| Auth Modes | In-Cluster ServiceAccount or Out-of-Cluster kubeconfig |
| Pod Lifecycle | Create, scale, and terminate pods programmatically |
| Health Probes | Kubernetes-native liveness and readiness endpoints |
| Namespace Isolation | Operate within a single namespace for security |
| StatefulSet Support | Optimized for game servers with persistent state |

### Redis State Store

| Feature | Description |
|---------|-------------|
| Shared State | Unified topology view with Velotim |
| Event History | Track scaling events with full audit trail |
| Multi-Proxy Coordination | Consistent state across proxy instances |
| Circuit Breaker | Graceful degradation when Redis is unavailable |
| Caching | Local cache for Redis failure resilience |

### Dashboard and API

| Feature | Description |
|---------|-------------|
| Web Dashboard | Visual cluster management with real-time updates |
| REST API | Programmatic access to all orchestration functions |
| WebSocket Events | Live streaming of scaling and health events |
| Token Authentication | Secure access with configurable auth tokens |
| IP Allowlist | WAF integration for trusted network access |

### Observability

| Feature | Description |
|---------|-------------|
| Prometheus Metrics | Export scaling and health metrics for Grafana |
| Structured Audit Logs | JSON logs with daily rotation |
| Slow Query Monitor | Identify database and Redis bottlenecks |
| Post-Mortem Reports | Automated incident documentation |

### Velotim Integration

| Feature | Description |
|---------|-------------|
| Soft Dependency | Works standalone or enhanced with Velotim |
| Unified Dashboard | Atlas nodes appear in Velotim Global Nerve Center |
| Shared Events | Scaling incidents visible across both systems |
| Pending Approval | New backends require operator confirmation |

---

## Installation

### As a Velocity Plugin

1. Build the project using your IDE (Maven integration):
   - IntelliJ IDEA: Build > Build Project
   - Eclipse: Project > Build Project

2. Copy the JAR to your Velocity plugins folder:
   ```bash
   cp atlas/target/atlas-1.0.jar /path/to/velocity/plugins/
   ```

3. Start Velocity to generate default configuration

4. Edit `plugins/atlas/config.yml` with your settings

5. Restart Velocity

### As a Standalone Sidecar

For large networks (1000+ players), run Atlas as a dedicated process:

```bash
# Basic startup
java -jar atlas-1.0.jar

# Custom configuration path
java -jar atlas-1.0.jar --config /etc/atlas/config.yml

# External kubeconfig for out-of-cluster operation
java -jar atlas-1.0.jar --kubeconfig /home/ops/.kube/config
```

### Quick Start (Linux)

```bash
# Download and install
curl -sSL https://github.com/revxiz/Atlas/releases/latest/download/atlas-1.0.jar -o atlas.jar

# Run setup wizard
java -jar atlas.jar --setup

# Start with systemd (production)
sudo cp atlas.service /etc/systemd/system/
sudo systemctl enable atlas
sudo systemctl start atlas
```

---

## Configuration

Configuration is stored in `config.yml` under the Atlas data directory.

```yaml
# ========================================
# Atlas Configuration
# https://github.com/revxiz/Atlas
# ========================================

# Config version - used for automatic migrations
config-version: 1

# ========================================
# Kubernetes Settings
# ========================================
kubernetes:
  enabled: true
  namespace: "minecraft"
  auth-mode: "auto"           # auto | in-cluster | kubeconfig
  kubeconfig-path: ""         # Path to kubeconfig (out-of-cluster only)
  
  # Pod template for new game servers
  pod-template:
    image: "itzg/minecraft-server:latest"
    cpu-request: "500m"
    cpu-limit: "2000m"
    memory-request: "1Gi"
    memory-limit: "4Gi"
    port: 25565

# ========================================
# Auto-Scaling
# ========================================
scaling:
  enabled: true
  cooldown-seconds: 60        # Minimum time between scaling actions
  min-replicas: 1
  max-replicas: 10
  
  # Custom scaling rules
  rules:
    - name: "player-scaling"
      metric: "player_count"
      operator: ">="
      threshold: 50
      action: "scale_up"
      replicas: 1
      cooldown: 120
      
    - name: "tps-protection"
      metric: "tps"
      operator: "<"
      threshold: 18.0
      action: "scale_up"
      replicas: 2
      cooldown: 60
      
    - name: "scale-down-idle"
      metric: "player_count"
      operator: "<"
      threshold: 5
      action: "scale_down"
      replicas: 1
      cooldown: 300

# ========================================
# Player Draining
# ========================================
drain:
  enabled: true
  timeout-seconds: 300        # 5 minutes default
  force-after-timeout: true   # Kick remaining players after timeout
  message: "Server shutting down. You will be transferred shortly."

# ========================================
# Redis State Store
# ========================================
redis:
  enabled: true
  host: "localhost"
  port: 6379
  password: "${ATLAS_REDIS_PASSWORD}"  # Environment variable support
  prefix: "atlas:"
  timeout-ms: 3000

# ========================================
# Dashboard
# ========================================
dashboard:
  enabled: true
  bind: "127.0.0.1"           # Use 0.0.0.0 for external access
  port: 9200
  auth-token: ""              # Required for external access
  allowed-ips: []             # WAF IP allowlist

# ========================================
# Health Probes
# ========================================
health:
  enabled: true
  port: 9201
  path: "/health"

# ========================================
# Prometheus Metrics
# ========================================
prometheus:
  enabled: true
  port: 9202
  path: "/metrics"

# ========================================
# Velotim Integration
# ========================================
velotim:
  enabled: true               # Auto-detect Velotim and integrate
  bridge-port: 9203           # Internal bridge for dashboard embedding
```

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| ATLAS_REDIS_PASSWORD | Redis authentication password |
| ATLAS_K8S_NAMESPACE | Kubernetes namespace override |
| ATLAS_AUTH_TOKEN | Dashboard authentication token |
| ATLAS_LOG_LEVEL | Logging level (DEBUG, INFO, WARN, ERROR) |

---

## Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| atlas_pods_total | Gauge | Total number of managed pods |
| atlas_pods_healthy | Gauge | Pods passing health checks |
| atlas_pods_draining | Gauge | Pods currently draining |
| atlas_scaling_events_total | Counter | Total scaling events |
| atlas_scaling_cooldown_active | Gauge | Whether cooldown is active |
| atlas_player_count | Gauge | Total players across managed pods |
| atlas_drain_timeout_remaining_seconds | Gauge | Time until forced drain |

---

## API Reference

### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/pods | List all managed pods |
| GET | /api/pods/{name} | Get pod details |
| POST | /api/pods/{name}/drain | Start draining a pod |
| POST | /api/pods/{name}/scale | Scale pod replicas |
| GET | /api/scaling/rules | List scaling rules |
| POST | /api/scaling/trigger | Manually trigger scaling |
| GET | /api/health | Cluster health summary |
| GET | /api/events | Recent scaling events |

### WebSocket Events

Connect to `/ws/events` for real-time updates:

```json
{
  "type": "SCALING_EVENT",
  "timestamp": "2026-02-08T10:30:00Z",
  "pod": "lobby-3",
  "action": "scale_up",
  "reason": "player_count > 50",
  "replicas": {"from": 2, "to": 3}
}
```

---

## Requirements

| Component | Version |
|-----------|---------|
| Java | 21+ |
| Velocity | 3.5.0+ |
| Kubernetes | 1.25+ |
| Redis | 6+ (optional, for multi-proxy) |

---

## Architecture

```
Player -> Velocity Proxy -> [Atlas Plugin]
                               |
                               v
                          Kubernetes API
                               |
               +---------------+---------------+
               |               |               |
            Pod-1           Pod-2           Pod-N
           (lobby)         (lobby)        (survival)
               |               |               |
               +-------+-------+
                       |
                       v
                 Redis State Store
                       |
                       v
                 Velotim Bridge
```

---

## Security Considerations

1. **Dashboard Access**: Bind to 127.0.0.1 by default. Use auth-token and IP allowlist for external access.

2. **Kubernetes RBAC**: Atlas requires specific permissions. Use the provided ClusterRole:
   ```yaml
   apiVersion: rbac.authorization.k8s.io/v1
   kind: ClusterRole
   metadata:
     name: atlas-orchestrator
   rules:
     - apiGroups: [""]
       resources: ["pods"]
       verbs: ["get", "list", "watch", "create", "delete"]
     - apiGroups: ["apps"]
       resources: ["statefulsets", "deployments"]
       verbs: ["get", "list", "watch", "update", "patch"]
   ```

3. **Redis Authentication**: Always use a password in production. Support environment variables for secrets.

4. **Audit Logging**: All scaling actions are logged with timestamps and reasons.

---

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| "Cannot connect to Kubernetes API" | Check auth-mode and kubeconfig path |
| "Redis connection failed" | Verify Redis is running and password is correct |
| "Scaling not triggering" | Check cooldown period and rule thresholds |
| "Pods not draining" | Verify drain timeout and force-after-timeout settings |

### Diagnostic Commands

```bash
# Check Atlas status
java -jar atlas.jar --status

# Test Kubernetes connectivity
java -jar atlas.jar --test-k8s

# Test Redis connectivity  
java -jar atlas.jar --test-redis

# Generate diagnostic report
java -jar atlas.jar --doctor
```

---

## License

MIT License - Copyright (c) 2026 xiz

---

## Related Projects

- [Velotim](https://github.com/revxiz/Velotim) - Adaptive network defense for Velocity
- [Velotim Heartbeat](https://github.com/revxiz/Velotim/tree/main/velotim-heartbeat) - Backend server health reporting

---

Made by xiz
