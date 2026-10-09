import { describe, expect, it } from 'vitest'
import {
  ALL,
  emptyFilters,
  filtersFromQuery,
  listRequest,
  pageFromQuery,
  queryOf,
  sameQuery,
  type ItemFilterState,
} from './itemFilters'

/** 空条件的基线：八个字段各自的默认值。 */
function empty(): ItemFilterState {
  return emptyFilters()
}

describe('emptyFilters', () => {
  it('每次返回新对象，但哨兵是同一个（=== ALL 恒成立；字符串原始值不经代理，天然稳定）', () => {
    const a = empty()
    const b = empty()

    expect(a).not.toBe(b)
    expect(a.warehouse).toBe(ALL)
    expect(b.warehouse).toBe(ALL)
    expect(a.stockStatus).toBe(ALL)
    expect(a.saleStatus).toBe(ALL)
    expect(a.venueId).toBe(ALL)
    expect(a.warnLevel).toBe(ALL)
    expect(a.kw).toBe('')
    expect(a.buyDateFrom).toBeNull()
    expect(a.buyDateTo).toBeNull()
  })
})

describe('filtersFromQuery', () => {
  it('空查询串：全部落回默认态', () => {
    expect(filtersFromQuery({})).toEqual(empty())
  })

  it('域内值原样取用', () => {
    const filters = filtersFromQuery({
      kw: 'HT9-A1X',
      warehouse: '2',
      stockStatus: '0',
      saleStatus: '3',
      warnLevel: '1',
      venueId: '7',
      buyDateFrom: '2026-01-01',
      buyDateTo: '2026-01-31',
    })

    expect(filters).toEqual({
      kw: 'HT9-A1X',
      warehouse: 2,
      stockStatus: 0,
      saleStatus: 3,
      warnLevel: 1,
      venueId: 7,
      buyDateFrom: '2026-01-01',
      buyDateTo: '2026-01-31',
    })
  })

  it('域外值落回「すべて」：URL 是用户可手改的输入，不落回会让下拉显空白且发出非法参数', () => {
    const filters = filtersFromQuery({
      warehouse: '99',
      stockStatus: '-1',
      saleStatus: 'abc',
      warnLevel: '',
    })

    expect(filters.warehouse).toBe(ALL)
    expect(filters.stockStatus).toBe(ALL)
    expect(filters.saleStatus).toBe(ALL)
    expect(filters.warnLevel).toBe(ALL)
  })

  it('会場 id 只认正整数：0 / 负数 / 非数字都不算选了会場', () => {
    expect(filtersFromQuery({ venueId: '0' }).venueId).toBe(ALL)
    expect(filtersFromQuery({ venueId: '-3' }).venueId).toBe(ALL)
    expect(filtersFromQuery({ venueId: '1.5' }).venueId).toBe(ALL)
  })

  it('日付只认 value-format 口径（YYYY-MM-DD），其余视为未选', () => {
    const filters = filtersFromQuery({
      buyDateFrom: '2026/01/01',
      buyDateTo: '2026-1-1',
    })

    expect(filters.buyDateFrom).toBeNull()
    expect(filters.buyDateTo).toBeNull()
  })

  it('同一键出现多次（数组）时不误判为字符串', () => {
    expect(filtersFromQuery({ kw: ['a', 'b'] }).kw).toBe('')
    expect(filtersFromQuery({ warehouse: ['1', '2'] }).warehouse).toBe(ALL)
  })

  it('页码不参与条件（它不是筛选，是列表位置）', () => {
    expect(filtersFromQuery({ page: '5' })).toEqual(empty())
  })
})

describe('pageFromQuery', () => {
  it('默认第 1 页；只有 ≥1 的整数才算数', () => {
    expect(pageFromQuery({})).toBe(1)
    expect(pageFromQuery({ page: '3' })).toBe(3)
    expect(pageFromQuery({ page: '0' })).toBe(1)
    expect(pageFromQuery({ page: '-2' })).toBe(1)
    expect(pageFromQuery({ page: 'x' })).toBe(1)
  })
})

describe('listRequest', () => {
  it('空条件只发页码与页大小，筛选参数一律 undefined', () => {
    expect(listRequest(empty(), 1, 20)).toEqual({
      kw: undefined,
      warehouse: undefined,
      stockStatus: undefined,
      saleStatus: undefined,
      venueId: undefined,
      buyDateFrom: undefined,
      buyDateTo: undefined,
      warnLevel: undefined,
      page: 1,
      size: 20,
    })
  })

  it('kw 两端去空白后发出；全空白视为未填', () => {
    expect(listRequest({ ...empty(), kw: '  HT9-A1X ' }, 1, 20).kw).toBe('HT9-A1X')
    expect(listRequest({ ...empty(), kw: '   ' }, 1, 20).kw).toBeUndefined()
  })

  it('「すべて」哨兵映射回 undefined（哨兵本身不发给服务端）', () => {
    const filters: ItemFilterState = {
      ...empty(),
      warehouse: 2,
      stockStatus: 0,
      saleStatus: 0,
      venueId: 7,
      warnLevel: 0,
    }

    const params = listRequest(filters, 2, 20)

    expect(params.warehouse).toBe(2)
    // 0 是**合法业务值**（在庫 / 未売却），不能被真值判断吞掉
    expect(params.stockStatus).toBe(0)
    expect(params.saleStatus).toBe(0)
    expect(params.warnLevel).toBe(0)
    expect(params.venueId).toBe(7)
    expect(params.page).toBe(2)
  })
})

describe('queryOf', () => {
  it('默认态不投影：裸路径（下钻详情后返回即回到同一屏幕）', () => {
    expect(queryOf(listRequest(empty(), 1, 20))).toEqual({})
  })

  it('只投影非默认项，page=1 不投影', () => {
    const filters: ItemFilterState = { ...empty(), kw: 'HT9-A1X', warehouse: 2 }

    expect(queryOf(listRequest(filters, 1, 20))).toEqual({ kw: 'HT9-A1X', warehouse: '2' })
    expect(queryOf(listRequest(filters, 2, 20))).toEqual({
      kw: 'HT9-A1X',
      warehouse: '2',
      page: '2',
    })
  })

  it('kw 的空白在投影前已被 trim：URL 与真正发出的检索参数同源', () => {
    const filters: ItemFilterState = { ...empty(), kw: '  HT9-A1X ' }

    expect(queryOf(listRequest(filters, 1, 20))).toEqual({ kw: 'HT9-A1X' })
  })

  it('size 恒定，不进 URL', () => {
    expect(queryOf(listRequest(empty(), 3, 20))).toEqual({ page: '3' })
  })
})

describe('sameQuery', () => {
  it('键数与逐键值都相同才算等价（等价就不 replace，免得空跑一次导航）', () => {
    expect(sameQuery({ kw: 'a', page: '2' }, { kw: 'a', page: '2' })).toBe(true)
    expect(sameQuery({ kw: 'a', page: '2' }, { kw: 'a' })).toBe(false)
    expect(sameQuery({ kw: 'a' }, { kw: 'b' })).toBe(false)
    expect(sameQuery({ kw: 'a', extra: 'x' }, { kw: 'a' })).toBe(false)
  })
})
