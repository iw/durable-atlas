# Odd|Workflow\<T\> \#1 \*beta\* \`v2\`

[All workflows](index.md)

| Property | Value |
|---|---|
| Workflow type | `` Odd\|Workflow<T> #1 *beta* `v2` `` |
| Interface | `` com.example.odd.`Odd Workflow` `` |
| Implementation | ``com.example.odd.`Odd Workflow`.Impl<T>`` |
| Module | `:odd` |
| Commit | `3f786850e387550fdab836ed7e6dc881de23001b` |
| Model mode | gradle |
| Nodes | 22 |
| Unknown nodes | 1 |

```mermaid
flowchart TD
    n_start(["start"])
    n_subgraph["Do[Thing](x){y}"]
    n_a_b{"items.any { it.qty #gt; 0 #amp;#amp; it.sku != #quot;N/A#quot; } #124;#124; flags[#quot;x#124;y#quot;] == true"}
    n_a_b_2{"orders.filter { it.status == Status.OPEN #amp;#amp; it.lines.isNotEmpty() }.sumOf { lin…"}
    subgraph n1_region_2 ["parallel#58; coroutineScope { /* #quot;fan#quot; out */ }"]
        n1(("fork"))
        subgraph n1_lane1 ["branch 1"]
            n1_join["join#35;1#59; drop table"]
        end
        subgraph n1_lane2 ["branch 2"]
            n1_region["it's #96;local#96; (local)"]
        end
        n1_join_2(("join"))
    end
    n_x_y[["Größe#124;Workflow"]]
    n__{{"await a#lt;b, flag#124;x<br/>timeout soon"}}
    n_class{{"timer #36;{config.delay}"}}
    n_style["side effect#58; lifestyle?#58;#quot;#35;fff#quot;#59; style fill#58;#35;f00#59;"]
    subgraph n2_region ["for ((k, v) in map) { // #quot;each#quot; entry }"]
        n2(("loop"))
        n3["signal external#58; #lt;b#gt;bold#lt;/b#gt; #amp;amp#59; #amp;lt#59;i#amp;gt#59;"]
        n4["nexus#58; svc/1.op#58;run#37;20now"]
    end
    n5["unknown#58; can't resolve #quot;handler#quot;<br/>handlers[#96;key#96;] (event)<br/>2 candidates"]
    n6{"version#58; fix/#35;42#58; #quot;quotes#quot;"}
    n7["compensation#58; undo#92;redo C#58;#92;temp#92;new"]
    n_end(["end"])
    n9(["continueAsNew"])
    n8(["fail<br/>java.lang.IllegalStateException#58; #quot;boom#quot;"])
    subgraph handlers ["handlers"]
        subgraph h_sig_set_flag ["signal set#quot;flag#quot; #124; #lt;now#gt; · writes#58; a#lt;b, flag#124;x"]
            n_h_start(["start"])
            n_h_end(["end"])
        end
    end
    n_start --> n_subgraph
    n_subgraph --> n_a_b
    n_a_b -->|"#quot;a#quot; #lt; b#59; c #gt; d"| n_a_b_2
    n_a_b -->|"when (x) { is Foo#lt;*#gt; -#gt; 1 }"| n1
    n_a_b_2 -->|"#96;backtick#96; #35; hash#59; semi#58; colon"| n1
    n_a_b_2 -->|"#37;#37;{init#58; {#quot;theme#quot;#58;#quot;dark#quot;}}#37;#37;"| n_x_y
    n1 --> n1_join
    n1 --> n1_region
    n1_join_2 -->|"#36;#36;x^2#36;#36; costs #36;5 or #36;{price}"| n_x_y
    n1_join --> n1_join_2
    n1_region --> n1_join_2
    n_x_y -->|"line one line two tabbed indented"| n__
    n__ -->|"naïve café → 日本語 ✓ 😀"| n_class
    n__ -->|"timeout#58; after #quot;soon#quot;"| n8
    n_class -->|"--#gt; #124;x#124; o--o x--x ==#gt; -.-#gt;"| n_style
    n_style -->|"a]]) b}} c)) d#quot;] e#124;"| n2
    n2 --> n3
    n2 -->|"end"| n5
    n3 -->|"'single' #quot;double#quot;"| n4
    n4 -->|"repeat"| n2
    n5 -->|"subgraph x [#quot;y#quot;]"| n6
    n5 -->|"kotlin.Result#lt;List#lt;Map#lt;String, Int#gt;#gt;#gt;"| n8
    n6 -->|"click a href #quot;javascript#58;alert(1)#quot;"| n7
    n6 --> n9
    n7 -->|"bell and #lt;br/#gt; break"| n_end
    n_h_start -.->|"a#lt;b, flag#124;x"| n__
    n_h_start --> n_h_end
    classDef unknown fill:#fff7e6,stroke:#b45309,color:#7c2d12,stroke-width:2px,stroke-dasharray:6 4
    class n5 unknown
```

## Child workflows

- `Größe|Workflow`: no diagram

## Unknown nodes

- `can't resolve "handler"`: ``handlers[`key`] (event)`` at ``odd/src/main/kotlin/com/example/odd/`Odd Workflow`.kt:23``; candidates: `com.example.B|C`, ``com.example.`odd name`.A``
