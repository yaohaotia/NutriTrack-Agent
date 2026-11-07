import request from "@/utils/request";

async function agentRequest(config) {
  const result = await request(config);
  if (result?.code !== 200) {
    const error = new Error(result?.message || "健康助手暂时无法完成请求");
    error.code = result?.code;
    throw error;
  }
  return result.data;
}

export const getAgentStatus = (signal) =>
  agentRequest({ url: "/api/agent/status", method: "get", signal });

export const sendAgentMessage = ({ message, sessionId }, signal) =>
  agentRequest({
    url: "/api/agent/chat",
    method: "post",
    data: { message, ...(sessionId ? { sessionId } : {}) },
    signal,
  });

export const getAgentSession = (sessionId, signal) =>
  agentRequest({
    url: `/api/agent/sessions/${encodeURIComponent(sessionId)}`,
    method: "get",
    signal,
  });

export const deleteAgentSession = (sessionId, signal) =>
  agentRequest({
    url: `/api/agent/sessions/${encodeURIComponent(sessionId)}`,
    method: "delete",
    signal,
  });
