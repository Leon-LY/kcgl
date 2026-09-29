import { describe, expect, it } from 'vitest'
import { availableActions, type ScanAction } from './inventoryActions'

/** 后端 InventoryStateMachine 边表的镜像锚点：任何一表漂移此用例先红。 */
describe('availableActions', () => {
  it('在库未上架：卖出/调拨/报废/上架标记/退回拍卖场，无顾客退回', () => {
    expect(availableActions(1, 0)).toEqual<ScanAction[]>([
      'sell',
      'transfer',
      'scrap',
      'markListed',
      'returnVenue',
    ])
  })

  it('在库在售：取消标记出现（1→3 流拍登记口），上架标记消失（0→1 边只认未上架）', () => {
    expect(availableActions(1, 1)).toEqual<ScanAction[]>([
      'sell',
      'transfer',
      'scrap',
      'markCanceled',
      'returnVenue',
    ])
  })

  it('在库成交（受注已标记未扫码出库）：仍可卖出/报废（在线下被直卖等场景）', () => {
    expect(availableActions(1, 2)).toEqual<ScanAction[]>(['sell', 'transfer', 'scrap', 'returnVenue'])
  })

  it('在库取消（流拍后）：重上入口回归（D-069：3→1 重上登记口）', () => {
    expect(availableActions(1, 3)).toEqual<ScanAction[]>(['sell', 'transfer', 'scrap', 'markListed', 'returnVenue'])
  })

  it('在途：仅退回拍卖场（从未入账，不占仓账）', () => {
    for (const sale of [0, 1, 2, 3]) {
      expect(availableActions(0, sale)).toEqual<ScanAction[]>(['returnVenue'])
    }
  })

  it('已出库且成交：仅顾客退回（成交→取消回原仓）', () => {
    expect(availableActions(2, 2)).toEqual<ScanAction[]>(['returnCustomer'])
  })

  it('已出库未成交（报废/退场后）：无任何动作（终态）', () => {
    for (const sale of [0, 1, 3]) {
      expect(availableActions(2, sale)).toEqual<ScanAction[]>([])
    }
  })
})
