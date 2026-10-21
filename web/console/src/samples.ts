/** 演示用配置：合成内容，不含任何真实来源的抓取规则 */
export const SAMPLE_CONFIGS: Record<string, string> = {
  demo: `// 演示配置：数据来自内置样例页面
const getData = (): WebConfig => /* break */ ({
  key: 'demo',
  name: '演示来源',
  homeUrl: 'https://demo.local/',

  getUrlReplace($) { return []; },

  getItems($) {
    const result = [];
    $('#list .card').each(function (index, element) {
      const $card = $(element);
      const $link = $card.find(ElementTypes.a);
      result.push({
        jumpUrl: helpElAttr($link, ElementAttr.href),
        title: helpElText($card.find('.title')),
        coverImg: helpElAttr($card.find(ElementTypes.img), ElementAttr.dataSrc),
        type: $card.hasClass('kind-comic') ? 'comic' : 'image',
      });
    });
    return result;
  },

  getPagination($) { return []; },

  getTags($) {
    return [{ title: '全部', url: this.homeUrl }];
  },

  async getDetailInfo(item, cheerio) {
    const html = await getHtml(item.jumpUrl);
    const $page = cheerio.load(html);
    return { detailType: 'text', details: [{ url: item.jumpUrl }] };
  },

  adapterLoadUrl(url) { return url; },

  adapterSearchUrl(key) {
    return this.homeUrl + 'search?q=' + encodeURIComponent(key);
  },
});
`,

  empty: `// 新建配置：只有骨架，按提示补齐
const getData = (): WebConfig => /* break */ ({
  key: 'new-config',
  name: '新建配置',
  homeUrl: 'https://example.com/',

  getUrlReplace($) { return []; },
  getItems($) { return []; },
  getPagination($) { return []; },
  getTags($) { return []; },
  async getDetailInfo(item, cheerio) { return null; },
  adapterLoadUrl(url) { return url; },
  adapterSearchUrl(key) { return this.homeUrl + key; },
});
`,
}
