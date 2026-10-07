import { describe, expect, it } from 'vitest'
import { renderErrorMessage, renderMessage, renderNoteJson, toDisplayMessage } from './errors'
import { ApiError } from './api'
import { i18n } from '@/i18n'

const t = i18n.global.t

describe('toDisplayMessage', () => {
  it('maps known error codes to localized messages', () => {
    const message = toDisplayMessage(new ApiError(401002, 'サーバー側メッセージ'), t)
    expect(message).toBe(t('errors.401002'))
  })

  it('renders the minutes-included message when a 423 lock carries remaining minutes', () => {
    const error = new ApiError(423001, 'locked', undefined, { remainingMinutes: 14 })
    expect(toDisplayMessage(error, t)).toBe(t('auth.lockedMinutes', { minutes: 14 }))
  })

  it('falls back to the error-code message when a 423 lock lacks remaining minutes', () => {
    const error = new ApiError(423001, 'locked')
    expect(toDisplayMessage(error, t)).toBe(t('errors.423001'))
  })

  it('falls back to the server message for unknown error codes (backend ja copy)', () => {
    const error = new ApiError(418001, '未知的服务端消息')
    expect(toDisplayMessage(error, t)).toBe('未知的服务端消息')
  })

  it('resolves code=0 local errors by the message symbol name', () => {
    expect(toDisplayMessage(new ApiError(0, 'NETWORK_ERROR'), t)).toBe(t('errors.NETWORK_ERROR'))
    expect(toDisplayMessage(new ApiError(0, 'INVALID_RESPONSE'), t)).toBe(t('errors.INVALID_RESPONSE'))
  })

  it('returns the generic error message for non-ApiError exceptions', () => {
    expect(toDisplayMessage(new Error('boom'), t)).toBe(t('common.error'))
  })
})

/**
 * 落库结构化消息（D-127）：临时切语言跑断言，退出时还原——本文件其余用例依赖
 * 默认 ja-JP（fallbackLocale 同为 ja-JP，键缺失会被静默兜底，故断言用具体文案
 * 而非 t(key) 自比）。
 */
function withLocale<T>(locale: string, run: () => T): T {
  const previous = i18n.global.locale.value
  i18n.global.locale.value = locale
  try {
    return run()
  } finally {
    i18n.global.locale.value = previous
  }
}

describe('renderMessage', () => {
  it('renders the active locale with params substituted', () => {
    const rendered = withLocale('zh-CN', () =>
      renderMessage(
        'imports.note.multiItemByOrder',
        { orderId: '10004876', codes: 'M-D8T-SR5・M-A68L-KW4' },
        t,
        '注文10004876（M-D8T-SR5・M-A68L-KW4）は複数商品のため単価が未分割です',
      ),
    )
    expect(rendered).toBe('订单10004876（M-D8T-SR5・M-A68L-KW4）包含多件商品，单价尚未拆分')
  })

  it('renders in Japanese when the active locale is ja-JP', () => {
    expect(
      renderMessage('imports.reason.fieldTooLong', { field: '商品コード', max: '32' }, t, ''),
    ).toBe('商品コードが32文字を超えています')
  })

  it('falls back to the stored text when the row predates the structured columns', () => {
    expect(renderMessage(null, null, t, '落札価格が不正です: abc')).toBe('落札価格が不正です: abc')
    expect(renderMessage(undefined, undefined, t, '落札価格が不正です: abc')).toBe(
      '落札価格が不正です: abc',
    )
  })

  it('falls back to the stored text when the code is not in the locale files', () => {
    expect(renderMessage('imports.reason.notARealKey', {}, t, 'サーバー側の原文')).toBe(
      'サーバー側の原文',
    )
  })

  it('renders an empty string when neither code nor stored text is present', () => {
    expect(renderMessage(null, null, t, null)).toBe('')
    expect(renderMessage(null, null, t, undefined)).toBe('')
  })

  it('keeps the sentence when a placeholder value is absent (vue-i18n drops the placeholder, not the message)', () => {
    // 后端每条 code 都带齐参数，此形态不应出现；固定下来是因为它比「回退原文」更隐蔽
    // ——只剩半句话而非整条日文，出问题时容易误判成翻译质量而非缺参。
    expect(renderMessage('imports.reason.fieldRequired', null, t, '')).toBe('が空です')
  })
})

describe('renderNoteJson', () => {
  it('renders every structured note in the active locale and re-joins with the locale separator', () => {
    const noteJson = JSON.stringify([
      {
        code: 'imports.note.multiItemByOrder',
        params: { orderId: '10004876', codes: 'M-D8T-SR5' },
        text: '注文10004876（M-D8T-SR5）は複数商品のため単価が未分割です',
      },
      {
        code: 'imports.note.multiItemByLine',
        params: { line: '9', codes: 'M-A68L-KW4' },
        text: '9行目（M-A68L-KW4）は複数商品のため単価が未分割です',
      },
    ])
    const rendered = withLocale('zh-CN', () => renderNoteJson(noteJson, null, t))
    expect(rendered).toBe('订单10004876（M-D8T-SR5）包含多件商品，单价尚未拆分、第9行（M-A68L-KW4）包含多件商品，单价尚未拆分')
  })

  it('uses a latin separator for en-US so no ideographic comma leaks in', () => {
    const noteJson = JSON.stringify([
      { code: 'imports.reason.rowFailed', params: {}, text: '行の処理中にエラーが発生しました' },
      { code: 'imports.reason.rowFailed', params: {}, text: '行の処理中にエラーが発生しました' },
    ])
    const rendered = withLocale('en-US', () => renderNoteJson(noteJson, null, t))
    expect(rendered).toBe('An error occurred while processing this row, An error occurred while processing this row')
  })

  it('falls back to the stored note for batches written before the structured column existed', () => {
    expect(renderNoteJson(null, '注文1（A・B）は複数商品のため単価が未分割です', t)).toBe(
      '注文1（A・B）は複数商品のため単価が未分割です',
    )
  })

  it('falls back to the stored note when the stored JSON is malformed or not an array', () => {
    expect(renderNoteJson('{not json', '旧批次的日文補注', t)).toBe('旧批次的日文補注')
    expect(renderNoteJson('{"code":"x"}', '旧批次的日文補注', t)).toBe('旧批次的日文補注')
    expect(renderNoteJson('[]', '旧批次的日文補注', t)).toBe('旧批次的日文補注')
  })

  it('returns null when there is neither structured nor stored text', () => {
    expect(renderNoteJson(null, null, t)).toBeNull()
  })

  it('keeps a structured entry readable when its stored text is the only thing available', () => {
    const noteJson = JSON.stringify([{ code: null, params: null, text: '行の処理中にエラーが発生しました' }])
    expect(renderNoteJson(noteJson, 'ignored', t)).toBe('行の処理中にエラーが発生しました')
  })
})

describe('renderErrorMessage', () => {
  it('parses the JSON params column and renders the active locale', () => {
    const rendered = withLocale('zh-CN', () =>
      renderErrorMessage(
        'imports.batch.excelHeaderMismatch',
        JSON.stringify({ column: '3', expected: '落札日', actual: '購入日' }),
        t,
        '原文',
      ),
    )
    expect(rendered).toBe('第3列的表头应为「落札日」，实际是「購入日」')
  })

  it('falls back to the stored message when the batch has no code', () => {
    expect(renderErrorMessage(null, null, t, '最初のワークシートが空です')).toBe(
      '最初のワークシートが空です',
    )
  })

  it('still renders when the params column is malformed or not an object', () => {
    expect(renderErrorMessage('imports.batch.failed', '{oops', t, '原文')).toBe(
      t('imports.batch.failed'),
    )
    // JSON 合法但不是对象（null / 数组）：参数视同缺失，句子照出而不是炸掉
    expect(renderErrorMessage('imports.batch.failed', 'null', t, '原文')).toBe(t('imports.batch.failed'))
    expect(renderErrorMessage('imports.batch.failed', '[1,2]', t, '原文')).toBe(t('imports.batch.failed'))
  })
})
