Component({
  properties: {
    min: { type: Number, value: 0 },
    max: { type: Number, value: 10 },
    values: { 
      type: Array, 
      value: [0, 10],
      observer: 'onValuesChange'
    },
    mode: { type: String, value: 'modelA' }, // modelA or modelB
    disabled: { type: Boolean, value: false }
  },

  data: {
    handles: [], // { x: 0, value: 0 }
    width: 0,
    left: 0
  },

  lifetimes: {
    attached() {
      this.initLayout();
    }
  },

  methods: {
    initLayout() {
      const query = this.createSelectorQuery();
      query.select('#slider-container').boundingClientRect((res) => {
        if (res) {
          this.setData({
            width: res.width,
            left: res.left
          });
          this.updateHandlesFromValues();
        }
      }).exec();
    },

    onValuesChange(newVal) {
      if (this.data.width > 0) {
        this.updateHandlesFromValues();
      }
    },

    updateHandlesFromValues() {
      const { min, max, values, width } = this.data;
      const range = max - min;
      if (range <= 0 || width <= 0) return;

      const handles = values.map(v => {
        let val = Math.max(min, Math.min(max, v));
        let x = ((val - min) / range) * width;
        return { x, value: val };
      });

      this.setData({ handles });
    },

    onTouchStart(e) {
      if (this.data.disabled) return;
      this.currentHandleIndex = e.currentTarget.dataset.index;
    },

    onTouchMove(e) {
      if (this.data.disabled || this.currentHandleIndex === undefined) return;
      
      const touch = e.touches[0];
      const pageX = touch.pageX; // We need pageX, but usually clientX is enough if no horiz scroll
      // In component, coordinate system can be tricky. 
      // boundingClientRect gives left relative to viewport.
      // e.touches[0].clientX is relative to viewport.
      
      const { width, left, min, max, handles } = this.data;
      let x = touch.clientX - left;
      
      // Clamp x
      x = Math.max(0, Math.min(width, x));
      
      // Calculate value
      const range = max - min;
      let value = (x / width) * range + min;
      
      // Round to 1 decimal
      value = Math.round(value * 10) / 10;
      
      // Snap x to value
      x = ((value - min) / range) * width;

      // Check Constraints
      const idx = this.currentHandleIndex;
      const newHandles = [...handles];
      
      if (this.properties.mode === 'modelA') {
        // [Min, Max]
        if (idx === 0) {
          // Moving Min: cannot exceed Max
          if (value > newHandles[1].value) value = newHandles[1].value;
        } else if (idx === 1) {
          // Moving Max: cannot be less than Min
          if (value < newHandles[0].value) value = newHandles[0].value;
        }
      } else if (this.properties.mode === 'modelB') {
        // [Near, Mid, Far]
        if (idx === 0) {
          if (value > newHandles[1].value) value = newHandles[1].value;
        } else if (idx === 1) {
          if (value < newHandles[0].value) value = newHandles[0].value;
          if (value > newHandles[2].value) value = newHandles[2].value;
        } else if (idx === 2) {
          if (value < newHandles[1].value) value = newHandles[1].value;
        }
      }

      // Recalculate x based on constrained value
      x = ((value - min) / range) * width;
      
      newHandles[idx] = { x, value };
      
      this.setData({ handles: newHandles });
      
      // Trigger event
      const values = newHandles.map(h => h.value);
      this.triggerEvent('change', { values });
    },

    onTouchEnd() {
      this.currentHandleIndex = undefined;
    }
  }
})
