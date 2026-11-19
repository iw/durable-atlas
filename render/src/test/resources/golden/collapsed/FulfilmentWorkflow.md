# FulfilmentWorkflow

> **Note:** This workflow has 34 nodes, more than the limit of 10, so its 2 regions are drawn as single nodes (23 nodes shown). Regions are loops, parallel sections and compensations.

| Property | Value |
|---|---|
| Workflow type | `FulfilmentWorkflow` |
| Interface | `com.example.fulfilment.FulfilmentWorkflow` |
| Implementation | `com.example.fulfilment.FulfilmentWorkflowImpl` |
| Module | `:fulfilment` |
| Commit | `3f786850e387550fdab836ed7e6dc881de23001b` |
| Model mode | gradle |
| Nodes | 34 |
| Unknown nodes | 2 |

```mermaid
flowchart TD
    n100(["start"])
    n101{"version#58; add-fraud-check"}
    n102["FraudCheck"]
    n103["side effect#58; generate idempotency key"]
    n104("parallel<br/>8 nodes hidden")
    n122(["fail<br/>PaymentDeclinedException"])
    n111{{"await approved<br/>timeout 1d"}}
    n112{"order.total #gt; 1000"}
    n113["signal external#58; notifyWarehouse"]
    n114[["ShippingWorkflow"]]
    n115{{"timer 1d 2h"}}
    n116("loop#58; do … while (!delivered)<br/>3 nodes hidden")
    n123["unknown#58; depth-limit<br/>auditTrail.record(order)"]
    n124["unknown#58; recursion"]
    n120(["continueAsNew"])
    n121(["end"])
    n125["compensation#58; ReleaseInventory"]
    subgraph handlers ["handlers"]
        subgraph h_sig_approve ["signal approve · writes#58; approved"]
            n200(["start"])
            n201(["end"])
        end
        subgraph h_sig_status ["signal statusChanged · writes#58; carrierStatus"]
            n210(["start"])
            n211(["end"])
        end
        subgraph h_upd_priority ["update setPriority · writes#58; priority"]
            n220(["start"])
            n221(["end"])
        end
    end
    n100 --> n101
    n101 -->|"v1"| n102
    n101 -->|"default"| n103
    n102 --> n103
    n103 --> n104
    n104 -->|"CardDeclinedException"| n122
    n104 --> n111
    n111 --> n112
    n111 -->|"timeout"| n125
    n112 -->|"true"| n113
    n112 -->|"false"| n114
    n113 --> n114
    n114 --> n115
    n115 --> n116
    n116 -->|"DELIVERED"| n123
    n116 -->|"timeout"| n123
    n123 --> n124
    n123 -->|"exception"| n121
    n124 --> n120
    n125 --> n122
    n200 -.->|"approved"| n111
    n200 --> n201
    n210 -.->|"carrierStatus"| n116
    n210 --> n211
    n220 --> n221
    classDef unknown fill:#fff7e6,stroke:#b45309,color:#7c2d12,stroke-width:2px,stroke-dasharray:6 4
    classDef collapsed fill:#eef2ff,stroke:#4338ca,color:#312e81,stroke-width:2px
    class n123,n124 unknown
    class n104,n116 collapsed
```

## Child workflows

- `ShippingWorkflow`: no diagram

## Unknown nodes

- `depth-limit`: `auditTrail.record(order)` at `fulfilment/src/main/kotlin/com/example/fulfilment/FulfilmentWorkflowImpl.kt:66`
- `recursion` at `fulfilment/src/main/kotlin/com/example/fulfilment/FulfilmentWorkflowImpl.kt:67`
