Component({
  properties: {
    type: { type: String, value: 'primary' },
    size: { type: String, value: 'md' },
    block: { type: Boolean, value: true },
    disabled: { type: Boolean, value: false },
    text: { type: String, value: '' }
  },
  methods: {
    onTap(e) {
      if (this.data.disabled) return
      this.triggerEvent('tap', e.detail)
    }
  }
})
