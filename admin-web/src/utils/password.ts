import type { FormItemRule } from 'element-plus'

/** 与后端 PasswordPolicy 一致：前端先提示，最终以后端校验为准 */
export const PASSWORD_HINT = '至少 10 位，大写字母、小写字母、数字、符号中至少包含 3 类，不能包含用户名。'

export function passwordStrengthError(password: string): string | null {
  if (password.length < 10) return '至少 10 位'
  const classes = [/[A-Z]/, /[a-z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(password)).length
  if (classes < 3) return '大写字母、小写字母、数字、符号中至少包含 3 类'
  return null
}

export function passwordRules(): FormItemRule[] {
  return [
    { required: true, message: '请输入新口令', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const error = passwordStrengthError(value ?? '')
        callback(error ? new Error(error) : undefined)
      },
      trigger: 'blur',
    },
  ]
}
