import "./style.css";
import "./workspace.css";
import WorkspaceShell from "./workspace-shell";
import {PersonalWorkspaceProvider} from "./personal-workspace";
export const metadata={title:"Knowledge Brain",description:"생각을 기록하고 지식의 연결을 탐색하는 공간"};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="ko"><body><PersonalWorkspaceProvider><WorkspaceShell>{children}</WorkspaceShell></PersonalWorkspaceProvider></body></html>}
