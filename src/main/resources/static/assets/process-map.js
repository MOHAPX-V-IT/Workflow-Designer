'use strict'

const svgNamespace = 'http://www.w3.org/2000/svg'

function editorStage(row, index) {
  const field = name => row.querySelector(`[name="${name}"]`)
  const label = name => field(name).selectedOptions[0]?.textContent.trim()
  const rule = field('ruleField').value
  return {
    row,
    title: field('stepName').value.trim() || `Этап ${index + 1}`,
    status: rule ? 'Условный этап' : 'Этап маршрута',
    progress: rule ? 'conditional' : 'pending',
    meta: [label('stepKind'), field('stepAssignee').value && label('stepAssignee'),
      field('stepHours').value && `${field('stepHours').value} ч`].filter(Boolean).join(' · '),
    condition: rule
      ? [label('ruleField'), label('ruleOperator'), field('ruleThreshold').value.trim()].filter(Boolean).join(' ')
      : '',
  }
}

function requestStage(row) {
  const { progress, kind, hours, assignee, due } = row.dataset
  return {
    row,
    title: row.querySelector('strong').textContent,
    status: row.querySelector('small').textContent,
    progress,
    meta: [kind, assignee, due ? `срок до ${due}` : `${hours} ч на выполнение`].filter(Boolean).join(' · '),
    condition: '',
  }
}

function createProcessMap(root) {
  const editor = root.closest('#blueprint-editor')
  const order = root.parentElement.querySelector('.route-order')
  const source = editor ? editor.querySelector('#step-rows') : order.querySelector('.step-list')
  const stage = root.querySelector('[data-orbit-stage]')
  const layer = root.querySelector('[data-orbit-nodes]')
  const links = root.querySelector('[data-orbit-links]')
  const play = root.querySelector('[data-orbit-play]')
  const detail = root.querySelector('[data-orbit-detail]')
  const related = root.querySelector('[data-orbit-related]')
  const edit = root.querySelector('[data-orbit-edit]')
  const hint = root.querySelector('[data-orbit-hint]')
  const motion = matchMedia('(prefers-reduced-motion: reduce)')
  const buttons = new Map()
  let stages = []
  let edges = []
  let active = null
  let autoRotate = !motion.matches
  let pointerOver = false
  let inView = false
  let radius = 0
  let angle = -90
  let focusAngle = null
  let frame = 0
  let lastFrame = 0

  function draw() {
    const points = stages.map((item, index) => {
      const radians = (angle + index * 360 / stages.length) * Math.PI / 180
      const x = radius * Math.cos(radians)
      const y = radius * Math.sin(radians)
      item.button.style.setProperty('--orbit-x', `${x}px`)
      item.button.style.setProperty('--orbit-y', `${y}px`)
      item.button.toggleAttribute('data-label-above', y > radius * 0.66)
      return [x, y]
    })
    for (const { from, to, path } of edges) {
      const largeArc = (to - from) * 2 > stages.length ? 1 : 0
      path.setAttribute('d', `M${points[from].join(' ')} A${radius} ${radius} 0 ${largeArc} 1 ${points[to].join(' ')}`)
    }
  }

  function wantsRotation() {
    return autoRotate && stages.length > 1 && !active && !pointerOver && !layer.contains(document.activeElement)
  }

  function scheduleDraw() {
    if (!frame && inView && !document.hidden && (focusAngle !== null || wantsRotation())) {
      frame = requestAnimationFrame(animate)
    }
  }

  function animate(now) {
    frame = 0
    const elapsed = lastFrame ? Math.min(now - lastFrame, 40) : 0
    lastFrame = now
    if (focusAngle !== null) {
      angle += (focusAngle - angle) * (1 - Math.exp(-elapsed / 85))
      if (Math.abs(focusAngle - angle) < 0.25) {
        angle = focusAngle
        focusAngle = null
      }
    } else if (wantsRotation()) {
      angle += elapsed * 0.006
    }
    draw()
    scheduleDraw()
    if (!frame) lastFrame = 0
  }

  function align(item) {
    const target = -90 - stages.indexOf(item) * 360 / stages.length
    const difference = ((target - angle) % 360 + 540) % 360 - 180
    focusAngle = motion.matches ? null : angle + difference
  }

  function renderDetail() {
    const index = stages.findIndex(item => item.row === active)
    const selected = stages[index]
    const neighbours = new Set()
    for (const { from, to, path } of edges) {
      const connected = index === from || index === to
      path.toggleAttribute('data-connected', connected)
      if (connected) neighbours.add(index === from ? to : from)
    }
    stages.forEach((item, position) => {
      item.button.setAttribute('aria-pressed', String(position === index))
      item.button.toggleAttribute('data-connected', neighbours.has(position))
    })
    detail.hidden = !selected
    hint.hidden = Boolean(selected)
    if (!selected) return
    root.querySelector('[data-orbit-status]').textContent = `Этап ${index + 1} · ${selected.status}`
    root.querySelector('[data-orbit-title]').textContent = selected.title
    root.querySelector('[data-orbit-meta]').textContent = selected.meta
    const condition = root.querySelector('[data-orbit-condition]')
    condition.textContent = selected.condition && `Условие: ${selected.condition}`
    condition.hidden = !selected.condition
    related.replaceChildren(...Array.from(neighbours, position => {
      const neighbour = stages[position]
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'orbit-control'
      button.textContent = `${position < index ? '←' : '→'} ${neighbour.title}`
      button.addEventListener('click', () => {
        select(neighbour)
        neighbour.button.focus({ preventScroll: true })
      })
      return button
    }))
  }

  function select(item) {
    active = item && active !== item.row ? item.row : null
    if (active) align(item)
    else focusAngle = null
    renderDetail()
    scheduleDraw()
  }

  function synchronize() {
    stages = Array.from(source.children, editor ? editorStage : requestStage)
    const rows = new Set(stages.map(item => item.row))
    for (const [row, button] of buttons) {
      if (rows.has(row)) continue
      button.remove()
      buttons.delete(row)
    }
    for (const [index, item] of stages.entries()) {
      let button = buttons.get(item.row)
      if (!button) {
        button = document.createElement('button')
        button.type = 'button'
        button.className = 'orbit-node'
        button.setAttribute('aria-controls', detail.id)
        const number = document.createElement('span')
        const title = document.createElement('span')
        title.className = 'orbit-node-title'
        button.append(number, title)
      }
      button.dataset.orbitNode = String(index + 1)
      button.dataset.progress = item.progress
      button.firstElementChild.textContent = String(index + 1)
      button.lastElementChild.textContent = item.title
      button.title = item.title
      button.setAttribute('aria-label', `Этап ${index + 1}: ${item.title} · ${item.status}`)
      button.onclick = () => select(item)
      if (layer.children[index] !== button) layer.insertBefore(button, layer.children[index] || null)
      buttons.set(item.row, button)
      item.button = button
    }
    layer.toggleAttribute('data-show-names', stages.length <= 6)
    const applicable = stages.flatMap((item, index) => item.progress === 'skipped' ? [] : [index])
    edges = applicable.slice(1).map((to, index) => {
      const path = document.createElementNS(svgNamespace, 'path')
      const from = applicable[index]
      path.toggleAttribute('data-conditional', [stages[from], stages[to]].some(item => item.progress === 'conditional'))
      return { from, to, path }
    })
    links.replaceChildren(...edges.map(edge => edge.path))
    if (!rows.has(active)) {
      active = null
      focusAngle = null
    }
    play.hidden = motion.matches || stages.length < 2
    renderDetail()
    draw()
    scheduleDraw()
  }

  function closeDetails() {
    if (!active) return
    const previous = buttons.get(active)
    select(null)
    previous?.focus({ preventScroll: true })
  }

  function updateRotationControl() {
    play.setAttribute('aria-pressed', String(autoRotate))
    scheduleDraw()
  }

  edit.hidden = !editor
  edit.addEventListener('click', () => active?.querySelector('[name="stepName"]').focus())
  root.querySelector('[data-orbit-close]').addEventListener('click', closeDetails)
  root.addEventListener('keydown', event => {
    if (event.key === 'Escape' && active) closeDetails()
  })
  play.addEventListener('click', () => {
    autoRotate = !autoRotate
    if (autoRotate && active) select(null)
    updateRotationControl()
  })
  motion.addEventListener('change', () => {
    autoRotate = !motion.matches
    if (motion.matches) focusAngle = null
    play.hidden = motion.matches || stages.length < 2
    updateRotationControl()
  })
  stage.addEventListener('pointerenter', event => { pointerOver = event.pointerType === 'mouse' })
  stage.addEventListener('pointerleave', () => { pointerOver = false; scheduleDraw() })
  layer.addEventListener('focusout', () => queueMicrotask(scheduleDraw))
  stage.addEventListener('click', event => {
    if (!event.target.closest('[data-orbit-node]')) select(null)
  })
  document.addEventListener('visibilitychange', scheduleDraw)
  new IntersectionObserver(([entry]) => {
    inView = entry.isIntersecting
    scheduleDraw()
  }).observe(stage)
  new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect
    radius = Math.max(0, Math.min(width, height) / 2 - 32)
    stage.style.setProperty('--orbit-diameter', `${radius * 2}px`)
    links.setAttribute('viewBox', `${-width / 2} ${-height / 2} ${width} ${height}`)
    draw()
  }).observe(stage)
  editor?.addEventListener('process-map:update', synchronize)
  editor?.addEventListener('input', event => {
    if (event.target.closest('[data-step-row]')) synchronize()
  })
  synchronize()
  updateRotationControl()
  root.hidden = false
  if (order) order.open = false
}

for (const map of document.querySelectorAll('[data-orbit]')) createProcessMap(map)
