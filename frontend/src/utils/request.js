import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/store/user'

const service = axios.create({
  baseURL: '/',
  timeout: 60000
})

const publicPaths = [
  '/user/login',
  '/user/captcha',
  '/user/register',
  '/user/register/step1',
  '/user/register/email/send',
  '/user/register/step2',
  '/user/forget-password',
  '/user/forget-password/send',
  '/user/forget-password/reset'
]
const isPublicRequest = config => publicPaths.some(p => (config?.url || '').startsWith(p))
let loginRedirectPending = false

// 页面已经由统一登录处理接管时，不再弹出业务失败提示。
export const isAuthError = error => error?.authHandled === true

function handleAuthenticationFailure(error) {
  error.authHandled = true
  const userStore = useUserStore()
  const token = userStore.token || localStorage.getItem('token')
  const authorization = error.config?.headers?.Authorization

  // 旧请求的响应不能清除用户刚刚重新登录得到的新凭证。
  if (token && authorization && authorization !== `Bearer ${token}`) return

  userStore.clearUser()
  if (token) ElMessage.warning('登录已失效，请重新登录')
  if (!loginRedirectPending && router.currentRoute.value.path !== '/login') {
    loginRedirectPending = true
    router.replace('/login').catch(() => { loginRedirectPending = false })
  }
}

// 请求拦截器
service.interceptors.request.use(config => {
  if (!isPublicRequest(config)) {
    const userStore = useUserStore()
    const token = userStore.token || localStorage.getItem('token')
    if (token) {
      loginRedirectPending = false
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
}, error => {
  return Promise.reject(error)
})

// 响应拦截器
service.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401 && !isPublicRequest(error.config)) {
      handleAuthenticationFailure(error)
    }
    return Promise.reject(error)
  }
)

export default service
