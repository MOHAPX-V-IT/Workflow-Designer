'use strict'

const editor = document.querySelector('#blueprint-editor')
if (editor) {
  const fieldRows = document.querySelector('#field-rows')
  const stepRows = document.querySelector('#step-rows')
  const addField = document.querySelector('#add-field')
  const addStep = document.querySelector('#add-step')
  const maxRows = 12

  function reorder(group) {
    const rows = Array.from(group.children)
    rows.forEach((row, index) => {
      row.querySelector('[data-number]').textContent = index + 1
      row.querySelector('[data-move="up"]').disabled = index === 0
      row.querySelector('[data-move="down"]').disabled = index === rows.length - 1
      row.querySelector('[data-remove]').disabled = rows.length === 1
    })
  }

  function syncRules() {
    const available = new Map()
    for (const row of fieldRows.children) {
      const type = row.querySelector('[data-field-type]').value
      const key = row.querySelector('[data-field-key]').value.trim()
      const label = row.querySelector('[name="fieldLabel"]').value.trim()
      row.querySelector('[data-field-options]').hidden = type !== 'SELECT'
      if (type === 'NUMBER' && row.querySelector('[data-field-required]').value === 'true' && key) {
        available.set(key, label || key)
      }
    }
    for (const row of stepRows.children) {
      const select = row.querySelector('[data-rule-field]')
      const current = select.value
      const options = [new Option('Всегда', '')]
      for (const [key, label] of available) options.push(new Option(label, key))
      if (current && !available.has(current)) options.push(new Option(current + ' · поле недоступно', current))
      select.replaceChildren(...options)
      select.value = current
      row.classList.toggle('conditional', current !== '')
      for (const detail of row.querySelectorAll('[data-rule-detail]')) detail.hidden = current === ''
      row.querySelector('[name="ruleThreshold"]').required = current !== ''
    }
    stepRows.dispatchEvent(new Event('process-map:update', { bubbles: true }))
  }

  function update() {
    reorder(fieldRows)
    reorder(stepRows)
    addField.disabled = fieldRows.children.length >= maxRows
    addStep.disabled = stepRows.children.length >= maxRows
    syncRules()
  }

  function append(group, templateId) {
    if (group.children.length >= maxRows) return
    const template = document.getElementById(templateId)
    const row = template.content.firstElementChild.cloneNode(true)
    if (group === fieldRows) {
      const used = new Set(Array.from(fieldRows.querySelectorAll('[data-field-key]'), input => input.value))
      let index = fieldRows.children.length + 1
      while (used.has('field' + index)) index += 1
      row.querySelector('[data-field-key]').value = 'field' + index
    }
    group.append(row)
    update()
    row.querySelector('input').focus()
  }

  addField.addEventListener('click', () => append(fieldRows, 'field-template'))
  addStep.addEventListener('click', () => append(stepRows, 'step-template'))
  editor.addEventListener('input', event => {
    if (event.target.matches('[data-field-key], [name="fieldLabel"]')) syncRules()
  })
  editor.addEventListener('change', event => {
    if (event.target.matches('[data-field-type], [data-field-required], [data-rule-field]')) syncRules()
  })
  editor.addEventListener('click', event => {
    const button = event.target.closest('button[data-move], button[data-remove]')
    if (!button || button.disabled) return
    const row = button.closest('[data-step-row], [data-field-row]')
    const group = row.parentElement
    if (button.hasAttribute('data-remove')) {
      const neighbour = row.nextElementSibling || row.previousElementSibling
      row.remove()
      neighbour?.querySelector('input')?.focus()
    } else if (button.dataset.move === 'up' && row.previousElementSibling) {
      group.insertBefore(row, row.previousElementSibling)
      row.querySelector('input').focus()
    } else if (button.dataset.move === 'down' && row.nextElementSibling) {
      group.insertBefore(row.nextElementSibling, row)
      row.querySelector('input').focus()
    }
    update()
  })
  update()
}
