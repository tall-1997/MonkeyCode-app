import { listModels } from '@/api/client';
import type { Model, ModelInterfaceType } from '@/api/types';
import type { EngineConfig } from '@/local/EngineBridge';

/** 本地 Agent 可用模型：优先私有模型（带 base_url/api_key），云端模型取回退端点。 */
export interface AgentModelOption {
  key: string;
  label: string;
  model: string; // 传给 LLM 的模型 id
  baseUrl: string;
  apiKey: string;
  interfaceType: ModelInterfaceType | 'auto';
  contextWindow: number;
  maxOutput: number;
  thinking: { enabled: boolean; effort: string };
  transport: 'direct';
}

/** 把移动端 Model 适配为本地 Agent 可用的 LLM 后端配置。 */
export function toAgentModel(m: Model): AgentModelOption | null {
  const modelId = m.model || '';
  if (!modelId) return null;

  // 私有模型：完整配置
  if (m.owner?.type === 'private' && m.base_url && m.api_key) {
    return {
      key: `private-${m.id || modelId}`,
      label: m.remark?.trim() || modelId,
      model: modelId,
      baseUrl: m.base_url,
      apiKey: m.api_key,
      interfaceType: (m.interface_type as ModelInterfaceType) || 'openai_chat',
      contextWindow: m.context_limit ?? 128000,
      maxOutput: m.output_limit ?? 32768,
      thinking: { enabled: !!m.thinking_enabled, effort: 'low' },
      transport: 'direct',
    };
  }

  // 共享模型缺少可供原生 runtime 使用的 gateway 凭据，避免把它伪装成空 key 直连后端。
  return null;
}

/** 加载可用的本地 Agent 模型列表。 */
export async function loadAgentModels(): Promise<AgentModelOption[]> {
  const all = await listModels();
  return all
    .filter((m) => m.id && !m.is_hidden && !m.is_free) // 排除隐藏/免费占位
    .map(toAgentModel)
    .filter((x): x is AgentModelOption => x !== null);
}

/** 从选中的模型生成本地引擎配置。 */
export function buildEngineConfig(opt: AgentModelOption, workDir: string, initialInput: string, systemPrompt?: string): EngineConfig {
  if (opt.interfaceType === 'auto') throw new Error('本地 Agent 需要明确的模型接口类型');
  return {
    workDir,
    modelConfig: {
      type: opt.interfaceType,
      model: opt.model,
      baseUrl: opt.baseUrl,
      apiKey: opt.apiKey,
      contextWindow: opt.contextWindow,
      maxOutput: opt.maxOutput,
      supportsImages: true,
      thinking: opt.thinking,
      interfaceType: opt.interfaceType,
    },
    systemPrompt: systemPrompt || defaultSystemPrompt(workDir),
    initialInput,
    skills: [],
  };
}

/** 默认本地 Agent 系统提示：声明可用工具与工作目录（对齐 OpenMinis/shiyi 的 agent 提示风格）。 */
export function defaultSystemPrompt(workDir: string): string {
  return [
    '你是运行在 Android 设备上的本地 AI 编程 Agent（MonkeyCode Local）。',
    `当前工作目录：${workDir}（或 PRoot Linux 沙箱的 /workspace）。`,
    '你可以使用以下工具完成用户任务：read_file、write_file、list_directory、exec_command、install_package、screenshot、gui_click、gui_type、get_accessibility_tree。',
    '执行命令优先走 Linux 沙箱（免 root），需要系统级操作时可使用 root 身份（若有权限）。',
    '请一步步思考，使用工具完成任务，并在最后总结结果。',
  ].join('\n');
}

/** 模型选择 PickerSheet 的 options 适配。 */
export function toAgentPickerOptions(models: AgentModelOption[]) {
  return models.map((m) => ({
    key: m.key,
    title: m.label,
    sub: `${m.interfaceType === 'anthropic' ? 'Anthropic' : m.interfaceType === 'openai_responses' ? 'OpenAI Responses' : 'OpenAI Chat'}`,
  }));
}
