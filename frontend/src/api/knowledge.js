import request from "@/utils/request";

async function call(config) {
  const result = await request(config);
  if (result?.code !== 200)
    throw new Error(result?.message || "知识库操作失败");
  return result.data;
}
export const knowledgeStatus = (signal) =>
  call({ url: "/api/knowledge/status", signal });
export const listDocuments = (signal) =>
  call({ url: "/api/knowledge/documents", signal });
export const getDocument = (id, signal) =>
  call({ url: `/api/knowledge/documents/${encodeURIComponent(id)}`, signal });
export const deleteDocument = (id, signal) =>
  call({
    url: `/api/knowledge/documents/${encodeURIComponent(id)}`,
    method: "delete",
    signal,
  });
export const importDocument = (data, id, signal) =>
  call({
    url: `/api/knowledge/documents${id ? `/${encodeURIComponent(id)}` : ""}`,
    method: id ? "put" : "post",
    data,
    timeout: 180000,
    signal,
  });
export const searchDocuments = (data, signal) =>
  call({ url: "/api/knowledge/search", method: "post", data, signal });
