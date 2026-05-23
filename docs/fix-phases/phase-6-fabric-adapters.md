# Phase 6：fabric 适配层

> **状态：已完成。** 所有修改项已在当前代码中实现，无需额外操作。

以下为已验证的修复清单：

- ✅ C-10: `refreshing` 已声明 `volatile`，`loadViewAsync` 的 `.whenComplete` 内 try-finally 确保异常路径重置
- ✅ C-4: 两个 fabric 的 `clicked()` 新增 `SWAP_REMOTE` 分支，`applyRemoteSwap` 完整实现原子交换客户端逻辑
- ✅ O-3: `getMaxStackSize` 直接查 `BuiltInRegistries.ITEM` 注册表，不再调用 `deserialize`
- ✅ O-8: `writeTag` 对 `ListTag` 走 `writeListSorted` 递归处理，嵌套 CompoundTag 也会排序

---

以下为原始修复计划（供参考）：

两个 fabric 目录同步修改。

## ExchangeMenu.java — C-10

- `fabric-26.1/src/.../container/ExchangeMenu.java`
- `fabric-1.21.11/src/.../container/ExchangeMenu.java`

`refreshing` 字段非 volatile，异常路径可能不重置，导致该菜单实例永久无法刷新。

**改**：声明 `volatile boolean refreshing`。`loadViewAsync` 的 `.whenComplete` 回调中，确保 `finally` 块重置 `refreshing = false`（当前只在正常路径重置）。

## ExchangeMenu.java — C-4 (fabric 侧配合)

`clicked()` 中的 action switch 需要新增 `SWAP_REMOTE` 分支。

**改**：

1. `ExchangeInteractionResult.Action` 枚举新增 `SWAP_REMOTE`

2. `ExchangeInteractionResult` 新增字段 `expectedItemId` 和工厂方法：
```java
public static ExchangeInteractionResult swapRemote(int slot, NeutralItem putItem,
                                                    int takeCount, String expectedItemId) {
    return new ExchangeInteractionResult(Action.SWAP_REMOTE, null,
            slot, takeCount, putItem, expectedItemId);
}
```

3. `ExchangeMenu.clicked()` 新增 case：
```java
case SWAP_REMOTE -> applyRemoteSwap(decision, player, input.getButtonNum());
```

4. `applyRemoteSwap` 实现：
```java
private void applyRemoteSwap(ExchangeInteractionResult decision,
                             ServerPlayer player, int hotbarSlot) {
    ItemStack inFlight = player.getInventory().getItem(hotbarSlot).copy();
    player.getInventory().setItem(hotbarSlot, ItemStack.EMPTY);
    NeutralItem putItem = neutralFromStack(inFlight);

    core.swapRemoteAsync(serverName, decision.getTargetSlot(), putItem,
            decision.getExpectedItemId(), decision.getCount(), playerContext)
        .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
            if (error != null || result == null || !result.isSuccess()) {
                player.getInventory().setItem(hotbarSlot, inFlight);
                if (result != null) sendError(player, result.getFailReason());
            } else {
                ItemStack taken = deserialize(result.getTakenItem());
                player.getInventory().setItem(hotbarSlot, taken);
            }
            refreshFromMemory();
        }));
}
```

逻辑与 PUT 一致：先扣快捷栏物品（乐观），失败归还。成功时把取回的物品放入同一个快捷栏格子。

---

## FabricItemSerializer.java — O-3

- `fabric-26.1/src/.../item/FabricItemSerializer.java`
- `fabric-1.21.11/src/.../item/FabricItemSerializer.java`

`getMaxStackSize(NeutralItem)` 内部调用 `deserialize(item)`，后者在物品不兼容时执行 `item.setIncompatible(true)`——纯查询方法产生副作用。

**改**：`getMaxStackSize` 使用 `BuiltInRegistries.ITEM.get(Identifier.tryParse(item.getItemId()))` 直接查注册表获取 `maxStackSize`，不经过 `deserialize`。

---

## FabricItemSerializer.java — O-8

- `fabric-26.1/src/.../item/FabricItemSerializer.java`
- `fabric-1.21.11/src/.../item/FabricItemSerializer.java`

`writeTag` 方法对 `ListTag` 直接调用原生 `tag.write(out)`，导致 ListTag 内嵌套的 CompoundTag 绕过排序逻辑。不同 JVM 实例中 HashMap 迭代顺序不同，相同物品在不同服务器间可能产生不同的 `extraData` 字节，影响 `sameStackKind` 比较。

受影响场景：附魔物品、属性修饰符、Lore 中嵌套的 CompoundTag。

**改**：`writeTag` 增加对 `ListTag` 的处理：
```java
private void writeTag(Tag tag, DataOutputStream out) throws IOException {
    if (tag instanceof CompoundTag compound) {
        writeCompoundSorted(compound, out);
        return;
    }
    if (tag instanceof ListTag list) {
        writeListSorted(list, out);
        return;
    }
    tag.write(out);
}

private void writeListSorted(ListTag list, DataOutputStream out) throws IOException {
    out.writeByte(list.getElementType());
    out.writeInt(list.size());
    for (Tag element : list) {
        writeTag(element, out);
    }
}
```
