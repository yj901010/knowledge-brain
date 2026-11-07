import {defineConfig} from "@playwright/test";
export default defineConfig({testDir:"./tests",timeout:180000,workers:2,use:{baseURL:"https://localhost:8443",headless:true,actionTimeout:15000},reporter:"list"});
