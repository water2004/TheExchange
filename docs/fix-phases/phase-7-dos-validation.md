# Phase 7：DoS 批量校验

> **状态：已完成。** 所有修改项已在当前代码中实现，无需额外操作。

以下为已验证的修复清单：

- ✅ D-2: `MessageCodec` 四个解码方法通过 `readListSize()` 校验 size ≤ `MAX_SLOTS(256)`，超出抛 IOException
- ✅ D-6: `NeutralItemBlobCodec.decodeList` 校验 size ≤ `MAX_LIST_ITEMS(10000)`，超出抛 IOException

---

以下为原始修复计划（供参考）：

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
