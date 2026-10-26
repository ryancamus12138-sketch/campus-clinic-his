import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 按钮级权限：<el-button v-permission="'charge:settle'">。
 * 没有权限时直接移除元素。注意这只是体验优化，真正的校验在后端 @PreAuthorize。
 */
export const permissionDirective: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    if (!useAuthStore().hasPermission(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
