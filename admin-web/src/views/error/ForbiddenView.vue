<template>
  <el-result icon="warning" title="没有访问权限" sub-title="当前账号没有该页面的权限，请联系管理员。">
    <template #extra>
      <el-button type="primary" @click="$router.replace('/')">返回工作台</el-button>
    </template>
  </el-result>
</template>
