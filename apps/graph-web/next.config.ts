import type {NextConfig} from "next";
// Bound static build resource use on the local development cluster.
const config:NextConfig={output:"export",basePath:"/graph",images:{unoptimized:true},poweredByHeader:false,experimental:{cpus:1}};export default config;
