# Phase 7：DoS 批量校验

## MessageCodec.java — D-2

解码方法中 `in.readInt()` 读取列表大小后直接用 `new ArrayList<>(size)` 分配，恶意值（`Integer.MAX_VALUE`）导致 OOM。涉及位置：

- `decodeSlotVersionsResponse` — `in.readInt()` → slot 数量
- `decodeQuerySlotsRequest` — `in.readInt()` → slot 列表大小
- `decodeSlotsStateResponse` — `in.readInt()` → slot 条目数
- `decodePushUpdate` — `in.readInt()` → changed slots 数量

**改**：所有 `readInt()` 读出的 size 与 `MAX_SLOTS = 256` 比较，超出抛 `IOException`。

## NeutralItemBlobCodec.java — D-6

`decodeList` 中 `in.readInt()` 读 size 后无上限检查。

**改**：`if (size > 10000) throw new IOException("Blob list too large: " + size)`。
