import { buildEngineConfig, toAgentModel } from '../agentBackend';

describe('agentBackend 本地可执行模型过滤', () => {
  test('保留凭据完整的私有模型并明确 direct transport', () => {
    expect(toAgentModel({
      id: '1',
      model: 'private-model',
      owner: { type: 'private' },
      base_url: 'https://api.example.com/v1',
      api_key: 'key',
      interface_type: 'openai_chat',
    })).toMatchObject({ model: 'private-model', transport: 'direct', interfaceType: 'openai_chat' });
  });

  test('过滤缺少原生 gateway 凭据的共享模型', () => {
    expect(toAgentModel({ id: '2', model: 'monkeycode-pro/model', owner: { type: 'public' } })).toBeNull();
  });

  test('拒绝 auto 接口进入 native runtime', () => {
    expect(() => buildEngineConfig({
      key: 'bad', label: 'bad', model: 'bad', baseUrl: 'https://api.example.com', apiKey: 'key',
      interfaceType: 'auto', contextWindow: 1000, maxOutput: 100, transport: 'direct',
      thinking: { enabled: false, effort: 'low' },
    }, '/workspace', 'hello')).toThrow('明确的模型接口类型');
  });
});
