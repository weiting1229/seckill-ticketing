import axios from 'axios'

/**
 * 全站共用 axios instance。
 * M1 之後在此加上:自動帶 access token、401 自動 refresh 後重放。
 */
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
})

export default http
