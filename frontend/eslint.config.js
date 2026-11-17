// P1-10：ESLint 10 只认 flat config；旧 .eslintrc.cjs 从装完依赖起就加载失败。
// 这里把原 legacy 配置等价迁到 flat 形态（Vue3 essential + TS recommended + skip-formatting）。
import { globalIgnores } from 'eslint/config'
import vue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import prettier from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfigWithVueTs(
  globalIgnores([
    'dist/**',
    'node_modules/**',
    'coverage/**',
    '**/*.d.ts',
    'public/**',
    'scripts/**'
  ]),
  ...vue.configs['flat/essential'].map(config => ({
    ...config,
    files: ['**/*.vue']
  })),
  vueTsConfigs.recommended,
  {
    languageOptions: {
      ecmaVersion: 'latest',
      globals: {
        window: 'readonly',
        document: 'readonly',
        navigator: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        console: 'readonly',
        process: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        requestAnimationFrame: 'readonly',
        cancelAnimationFrame: 'readonly',
        URL: 'readonly',
        URLSearchParams: 'readonly',
        Blob: 'readonly',
        File: 'readonly',
        FormData: 'readonly',
        fetch: 'readonly',
        Headers: 'readonly',
        Response: 'readonly',
        EventSource: 'readonly',
        WebSocket: 'readonly',
        Audio: 'readonly',
        Image: 'readonly',
        atob: 'readonly',
        btoa: 'readonly',
        structuredClone: 'readonly',
        crypto: 'readonly',
        TextEncoder: 'readonly',
        TextDecoder: 'readonly',
        AbortController: 'readonly',
        DOMParser: 'readonly',
        Node: 'readonly',
        Element: 'readonly',
        HTMLElement: 'readonly',
        HTMLInputElement: 'readonly',
        MouseEvent: 'readonly',
        KeyboardEvent: 'readonly',
        CustomEvent: 'readonly',
        IntersectionObserver: 'readonly',
        ResizeObserver: 'readonly',
        MutationObserver: 'readonly',
        matchMedia: 'readonly',
        getComputedStyle: 'readonly'
      }
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/no-unused-vars': 'error',
      'vue/no-mutating-props': 'error',
      'vue/require-default-prop': 'error',
      'vue/require-prop-types': 'error',
      // 有意不使用的形参 / catch 参数用 _ 前缀显式标注（框架回调签名兼容）
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
        varsIgnorePattern: '^_'
      }],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/explicit-function-return-type': 'off',
      '@typescript-eslint/explicit-module-boundary-types': 'off',
      'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
      'no-debugger': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
      'prefer-const': 'error',
      'no-var': 'error',
      'object-shorthand': 'error',
      'prefer-template': 'error'
    }
  },
  prettier
)
