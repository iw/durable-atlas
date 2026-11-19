# OrderWorkflow: coverage

[All workflows](index.md)

> Hit counts from 5 executions; \*grey\* was never reached.

| Property | Value |
|---|---|
| Workflow type | `OrderWorkflow` |
| Interface | `com.example.orders.OrderWorkflow` |
| Implementation | `com.example.orders.OrderWorkflowImpl` |
| Module | `:orders:workflows` |
| Commit | `3f786850e387550fdab836ed7e6dc881de23001b` |
| Model mode | gradle |
| Nodes | 23 |
| Unknown nodes | 1 |
| Executions | 5 |
| Unmapped events | 0 |

```mermaid
flowchart TD
    n00(["start<br/>×5"])
    n01["ReserveStock<br/>×5"]
    n02{{"await cancelled, paid<br/>timeout 30m<br/>×5"}}
    n03{"branch<br/>×4"}
    subgraph n04_region ["for each line item"]
        n04(("loop<br/>×3"))
        n05["ShipItem<br/>×7"]
        n06["RecordShipment (local)<br/>×7"]
    end
    n07[["InvoiceWorkflow<br/>×3"]]
    n08["unknown#58; dynamic-dispatch<br/>2 candidates<br/>×3"]
    n09(["end<br/>×3"])
    n10["ReleaseStock"]
    n12(["fail<br/>PaymentTimeoutException<br/>×1"])
    subgraph handlers ["handlers"]
        subgraph h_sig_cancel ["signal cancelOrder · writes#58; cancelled"]
            n20(["start"])
            n21(["end"])
        end
        subgraph h_sig_paid ["signal paymentReceived · writes#58; paid · ×4"]
            n30(["start<br/>×4"])
            n31(["end<br/>×4"])
        end
        subgraph h_qry_status ["query getStatus · reads#58; status"]
            n40(["start"])
            n41(["end"])
        end
        subgraph h_upd_address ["update + validator changeAddress · reads#58; status · writes#58; address · ×2"]
            n60(["start<br/>validator<br/>×2"])
            n61(["end<br/>×2"])
            n50(["start<br/>update<br/>×2"])
            n51["ValidateAddress<br/>×2"]
            n52(["end<br/>×2"])
        end
    end
    n00 --> n01
    n01 --> n02
    n02 --> n03
    n02 -->|"timeout"| n12
    n03 -->|"paid"| n04
    n03 -->|"cancelled"| n10
    n04 --> n05
    n04 --> n07
    n05 --> n06
    n06 -->|"repeat"| n04
    n07 --> n08
    n08 --> n09
    n10 --> n09
    n20 -.->|"cancelled"| n02
    n20 --> n21
    n30 -.->|"paid"| n02
    n30 --> n31
    n40 --> n41
    n50 --> n51
    n51 --> n52
    n60 --> n61
    classDef unknown fill:#fff7e6,stroke:#b45309,color:#7c2d12,stroke-width:2px,stroke-dasharray:6 4
    classDef dimmed fill:#f3f4f6,stroke:#d1d5db,color:#9ca3af
    class n08 unknown
    class n10,n20,n21,n40,n41 dimmed
    style h_sig_cancel fill:#f3f4f6,stroke:#d1d5db,color:#9ca3af
    style h_qry_status fill:#f3f4f6,stroke:#d1d5db,color:#9ca3af
    linkStyle 5,12,13,14,17 stroke:#d1d5db
```

## Child workflows

- `InvoiceWorkflow`: no diagram

## Unknown nodes

- `dynamic-dispatch` at `orders/workflows/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt:61`; candidates: `com.example.orders.EmailNotifier`, `com.example.orders.SmsNotifier`
