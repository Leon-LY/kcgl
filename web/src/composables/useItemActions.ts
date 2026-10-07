import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toDisplayMessage } from '@/utils/errors'
import { newClientId } from '@/utils/id'
import { parseAmount } from '@/utils/normalize'
import {
  markCanceledItem,
  markListedItem,
  returnItem,
  scrapItem,
  sellItem,
  transferItem,
} from '@/utils/api'
import type { ActionResult } from '@/utils/api'
import type { ScanAction } from '@/utils/inventoryActions'

/**
 * 七动作的载荷、前置校验与提交（M3-④ 扫码页与商品一覧行内动作共用，D-129）。
 *
 * 抽出来的是**逻辑**不是外观：两个壳的操作入口分别在移动端（手写覆层，为触控 48px
 * 命中区与全屏取景让路）与桌面端（Element Plus 弹窗），外观本就该各走各的；但
 * 「卖出带落札价、报废必填理由、调拨选仓库、退货分方向」这套载荷契约，以及
 * 「动作×商品幂等键失败重试复用、成功才作废」的 7.0 幂等语义，复制一份就是复制一处
 * 会漂移的地方——尤其是幂等键：漏了它不会立刻报错，只会在网络抖动重试时多落一笔。
 *
 * 目标件由 `open` 注入并自持到 `close`：调用方不必在提交时再回传一次目标。
 */

/** open 所需的最小目标形状：ItemResponse 与 ItemSearchRow 都结构性满足。 */
export interface ItemActionTarget {
  id: number
  itemCode: string
  warehouse: number
}

/** 落札价上限：与后端校验同口径（超出即拒，避免把明显误输的金额送到服务端）。 */
const MAX_UNIT_PRICE = 99_999_999

export interface UseItemActionsOptions {
  /**
   * 提交成功后的回调：调用方据此刷新自己的列表/卡片并出成功提示。
   * 弹层自身不弹消息——移动端用横幅、桌面端用 ElMessage，收敛不到一处。
   */
  onDone: (action: ScanAction, result: ActionResult) => void | Promise<void>
}

export function useItemActions(options: UseItemActionsOptions) {
  const { t } = useI18n()

  const activeAction = ref<ScanAction | null>(null)
  const target = ref<ItemActionTarget | null>(null)
  const busy = ref(false)
  const error = ref('')

  const soldPriceInput = ref('')
  const scrapReason = ref('')
  const transferTo = ref(0)
  const returnNote = ref('')

  /**
   * 文案块名：多动作共用块在此归并（return 双向→return、markListed→listed、
   * markCanceled→canceled）；sell/scrap/transfer 动作名与块名一致直用。
   * （回归：直拼 `scan.${action}.title` 曾让 markListed 弹层渲染原始键名——
   * i18n 块名是 listed 而非 markListed，E2E 只断言过按钮从未开过弹层。）
   *
   * 键落在 `scan.*` 命名空间下而非另起 `items.action.*`：同一动作在两壳必须说同一句话
   * （尤其 confirm 与 hint），各存一份就是给「同一次报废在两端叫法不同」留了口子。
   */
  const blockKey = computed(() => {
    switch (activeAction.value) {
      case 'returnCustomer':
      case 'returnVenue':
        return 'return'
      case 'markListed':
        return 'listed'
      case 'markCanceled':
        return 'canceled'
      default:
        return activeAction.value ?? ''
    }
  })

  const titleKey = computed(() => `scan.${blockKey.value}.title`)
  const confirmKey = computed(() => `scan.${blockKey.value}.confirm`)

  function open(action: ScanAction, next: ItemActionTarget): void {
    // 每次打开都从干净态起步：残留的上次输入会被当成本次的载荷提交
    error.value = ''
    soldPriceInput.value = ''
    scrapReason.value = ''
    returnNote.value = ''
    if (action === 'transfer') {
      transferTo.value = next.warehouse === 1 ? 2 : 1
    }
    target.value = next
    activeAction.value = action
  }

  /** busy 中不关：请求在途时关掉弹层，失败信息就没有落点了。 */
  function close(): void {
    if (busy.value) {
      return
    }
    activeAction.value = null
    target.value = null
  }

  /** 动作×商品幂等键：生成后保留到成功为止（失败重试复用同键，7.0）。 */
  const idempotencyKeys = new Map<string, string>()

  function clientKeyFor(action: ScanAction, itemId: number): string {
    const mapKey = `${action}:${itemId}`
    const existing = idempotencyKeys.get(mapKey)
    if (existing != null) {
      return existing
    }
    const key = newClientId()
    idempotencyKeys.set(mapKey, key)
    return key
  }

  /**
   * 前置校验通过后提交；失败时**弹层保持打开**，直接重试即安全重放同键（7.0），
   * 不需要从头再来一遍。
   */
  async function confirm(): Promise<void> {
    const action = activeAction.value
    const current = target.value
    if (action == null || current == null || busy.value) {
      return
    }
    const itemId = current.id
    const clientReqId = clientKeyFor(action, itemId)

    if (action === 'scrap' && scrapReason.value.trim() === '') {
      error.value = t('scan.scrap.reasonRequired')
      return
    }
    let soldPrice: number | undefined
    if (action === 'sell' && soldPriceInput.value.trim() !== '') {
      const parsed = parseAmount(soldPriceInput.value)
      if (parsed == null || parsed < 1 || parsed > MAX_UNIT_PRICE) {
        error.value = t('scan.sell.priceInvalid')
        return
      }
      soldPrice = parsed
    }

    busy.value = true
    error.value = ''
    try {
      let result: ActionResult
      if (action === 'sell') {
        result = await sellItem(itemId, clientReqId, soldPrice)
      } else if (action === 'scrap') {
        result = await scrapItem(itemId, clientReqId, scrapReason.value.trim())
      } else if (action === 'transfer') {
        result = await transferItem(itemId, clientReqId, transferTo.value)
      } else if (action === 'markListed') {
        result = await markListedItem(itemId, clientReqId)
      } else if (action === 'markCanceled') {
        result = await markCanceledItem(itemId, clientReqId)
      } else {
        const direction = action === 'returnCustomer' ? 1 : 2
        const note = returnNote.value.trim()
        result =
          note === ''
            ? await returnItem(itemId, clientReqId, direction)
            : await returnItem(itemId, clientReqId, direction, note)
      }
      idempotencyKeys.delete(`${action}:${itemId}`)
      activeAction.value = null
      target.value = null
      await options.onDone(action, result)
    } catch (err) {
      error.value = toDisplayMessage(err, t)
    } finally {
      busy.value = false
    }
  }

  return {
    activeAction,
    busy,
    error,
    soldPriceInput,
    scrapReason,
    transferTo,
    returnNote,
    titleKey,
    confirmKey,
    open,
    close,
    confirm,
  }
}
