// 演示用配置：全部数据来自应用内置的样例页面，不访问任何外部站点。
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
        type: $card.hasClass('kind-comic') ? 'comic'
            : ($card.hasClass('kind-text') ? 'text' : 'video'),
        tags: [
          { title: $card.hasClass('vip') ? 'VIP' : '免费', url: '' }
        ]
      });
    });
    return result;
  },

  getPagination($) {
    const pages = [];
    $('#pager li').each(function (index, element) {
      const $item = $(element);
      pages.push({
        title: helpElText($item),
        url: helpElAttr($item.find('a'), 'href'),
        isCurrent: $item.hasClass('active')
      });
    });
    return pages;
  },

  getTags($) {
    return [
      { title: '全部', url: this.homeUrl },
      { title: '—', url: '' }
    ];
  },

  async getContents(url, cheerio) {
    const html = await getHtml(url);
    const $page = cheerio.load(html);
    const chapters = [];
    $page('.chapter a').each(function (index, element) {
      const $a = $(element);
      chapters.push({
        title: helpElText($a),
        url: helpElAttr($a, 'href'),
        isCurrent: $a.hasClass('current')
      });
    });
    return chapters;
  },
  async getDetailInfo(item, cheerio) {
    const html = await getHtml(item.jumpUrl);
    const $page = cheerio.load(html);

    if (item.jumpUrl.indexOf('comic') >= 0) {
      const images = [];
      $page('.thumb a').each(function (index, element) {
        const $a = $(element);
        images.push({
          url: helpElAttr($a, 'href'),
          fullUrl: helpElAttr($a, 'data-full'),
          title: helpElText($a)
        });
      });
      return { detailType: 'comic', details: images };
    }

    if (item.jumpUrl.indexOf('text') >= 0) {
      return {
        detailType: 'text',
        details: [{ url: item.jumpUrl, title: helpElText($page('h1')) }]
      };
    }

    const sources = [];
    $page('video source').each(function (index, element) {
      const $source = $(element);
      sources.push({
        type: 'm3u8',
        url: helpElAttr($source, 'src'),
        quality: helpElAttr($source, 'label')
      });
    });
    return { detailType: 'm3u8', details: sources };
  },

  async getTextContent(url, cheerio) {
    const html = await getHtml(url);
    const $page = cheerio.load(html);
    return {
      title: helpElText($page('h1')),
      html: $page('.content').html(),
      prevUrl: helpElAttr($page('.nav a.prev'), 'href'),
      nextUrl: helpElAttr($page('.nav a.next'), 'href')
    };
  },

  adapterLoadUrl(url) { return url; },

  adapterSearchUrl(key) {
    return this.homeUrl + 'search?q=' + encodeURIComponent(key);
  }
});
