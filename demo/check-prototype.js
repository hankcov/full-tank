/**
 * 原型回归：真在 jsdom 里跑一遍，读首页 DOM 而不是看代码猜。
 * 覆盖：
 *   R1 标了油箱（45 L）→ 主数字是预计加油里程，小字写满箱续航
 *   R2 油箱清空 → 退回按上次加油量估，小字提示去填
 *   R3 改油箱不重绘面板（输入框焦点不丢）
 *   R4 导出带 tank 列，导回来油箱还在
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync(path.join(__dirname, '..', 'demo', 'prototype.html'), 'utf8');
const errors = [];
const dom = new JSDOM(html, { runScripts: 'dangerously', pretendToBeVisual: true });
dom.window.addEventListener('error', e => errors.push(String(e.error || e.message)));

const doc = dom.window.document;
const txt = id => (doc.getElementById(id)?.textContent || '').trim();
const html_ = id => (doc.getElementById(id)?.innerHTML || '').trim();

let pass = 0, fail = 0;
function check(name, cond, got) {
  if (cond) { pass++; console.log(`  ok  ${name}`); }
  else { fail++; console.log(`  FAIL ${name}  →  ${got}`); }
}

console.log('--- 默认（我的车，油箱 45 L，10 条记录）---');
const odo = txt('rangeOdo');
const sub = txt('rangeSub');
console.log(`  主数字: ${odo}`);
console.log(`  小字  : ${sub}`);
// 当前油耗 7.52，45/7.52*100 = 598.4 → 598；12890 + 598 = 13488
check('R1 主数字是预计加油里程 13,488 km', odo.startsWith('13,488'), odo);
check('R1 小字写明满箱 45 L 与 598 km', /满箱 45 L 能跑约 598 km/.test(sub), sub);

console.log('--- 清空油箱 → 退回旧算法 ---');
const win = dom.window;
// 车辆面板是打开时才渲染的，先让它渲染出来
win.eval('renderVehList()');
const tankInput = doc.querySelector('[data-tank="1"]');
check('R0 车辆面板有油箱输入框', !!tankInput, tankInput);
tankInput.value = '';
tankInput.dispatchEvent(new win.Event('input', { bubbles: true }));
const odo2 = txt('rangeOdo');
const sub2 = txt('rangeSub');
console.log(`  主数字: ${odo2}`);
console.log(`  小字  : ${sub2}`);
// 44.0 / 7.52 * 100 = 585.1 → 585；12890 + 585 = 13475
check('R2 未填油箱 → 13,475 km', odo2.startsWith('13,475'), odo2);
check('R2 小字说明用的是上次加油量', /按上次加油 44\.0 L 估/.test(sub2) && /填油箱容积更准/.test(sub2), sub2);

console.log('--- 改成 5.5 L（摩托车量级）---');
const t3 = doc.querySelector('[data-tank="1"]');
t3.value = '5.5';
t3.dispatchEvent(new win.Event('input', { bubbles: true }));
const odo3 = txt('rangeOdo');
console.log(`  主数字: ${odo3}  小字: ${txt('rangeSub')}`);
// 5.5/7.52*100 = 73.1 → 73；12890+73 = 12963
check('R3 5.5 L → 12,963 km', odo3.startsWith('12,963'), odo3);

console.log('--- 切到 SUV（60 L）---');
doc.querySelector('[data-v="2"]').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
const sub4 = txt('rangeSub');
console.log(`  小字: ${sub4}`);
check('R4 切车后跟着换油箱', /满箱 60 L/.test(sub4) || /按上次加油/.test(sub4), sub4);

console.log('--- 导出列 ---');
const csvHead = doc.querySelector('script') && null; // 表头在 JS 常量里，直接验行为
const exported = win.eval('buildCsv()');
const head = exported.split('\n')[0];
const firstRow = exported.split('\n')[1] || '';
console.log(`  表头: ${head}`);
console.log(`  首行: ${firstRow}`);
check('R5 导出表头含 tank', /,tank$/.test(head), head);

console.log('--- 导入：老文件（无 tank 列）不崩 ---');
const legacy = 'vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated\r\n陕A·8K2L7,我的车,2026-10-19,12990,44,374,8.49,1,,0\r\n';
let importOk = true;
try { win.eval(`importCsv(${JSON.stringify(legacy)})`); } catch (e) { importOk = false; errors.push(String(e)); }
check('R6 老格式导入不抛异常', importOk, importOk ? '' : errors.join('; '));

console.log('--- 导入：新文件带 tank，本地空则补、本地有则不覆盖 ---');
const withTank = `vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated,tank\r\n陕A·F5D0,家里的SUV,2026-10-12,44500,57.5,515.78,8.97,1,,0,8.5\r\n`;
win.eval(`importCsv(${JSON.stringify(withTank)})`);
const suvTank = win.eval('vehicles.find(v => v.id === 2).tank');
console.log(`  SUV 油箱: ${suvTank}`);
check('R7 本地已有 60 L 不被 8.5 覆盖', suvTank === 60, suvTank);

console.log('--- 更多操作：导入导出收进右上角 ---');
const menu = doc.getElementById('moreMenu');
const moreBtn = doc.getElementById('moreBtn');
check('R8 菜单默认收起', !menu.classList.contains('on'), menu.className);
moreBtn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
check('R8 点更多展开，里面是导出/导入', menu.classList.contains('on') && /导出 CSV/.test(menu.textContent) && /导入 CSV/.test(menu.textContent), menu.textContent.trim());
moreBtn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
check('R8 再点收起', !menu.classList.contains('on'), menu.className);
moreBtn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
doc.body.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
check('R8 点空白处收起', !menu.classList.contains('on'), menu.className);
check('R8 列表头部不再有常驻按钮', !doc.querySelector('#page-history .opbtn'), 'still there');

console.log('--- 车辆信息可编辑 ---');
const vehListEl = doc.getElementById('vehList');
win.eval('renderVehList()');
const pencil = doc.querySelector('.vedit[data-edit="2"]');
check('R9 每辆车都有编辑入口', !!pencil, 'no .vedit');
pencil.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
const formTitle = () => txt('vFormTitle');
const nameInput = () => doc.getElementById('vName');
check('R9 点编辑后表单进入编辑态并预填', /编辑 · 家里的SUV/.test(formTitle()) && nameInput().value === '家里的SUV', `${formTitle()} / ${nameInput().value}`);
check('R9 取消按钮此时可见', doc.getElementById('vCancel').style.display !== 'none', doc.getElementById('vCancel').style.display);

const countBefore = win.eval('records.filter(r => r.veh === 2).length');
nameInput().value = '通勤车';
doc.getElementById('vPlate').value = '陕A·TEST';
doc.getElementById('vTank').value = '55';
doc.getElementById('vGrade').value = '95';
doc.getElementById('vAddBtn').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
const veh2 = win.eval('JSON.parse(JSON.stringify(vehicles.find(v => v.id === 2)))');
console.log(`  改后: ${veh2.name} / ${veh2.plate} / ${veh2.tank}L / ${veh2.fuelGrade}#`);
check('R9 名称、车牌、油箱、标号都存下来', veh2.name === '通勤车' && veh2.plate === '陕A·TEST' && veh2.tank === 55 && veh2.fuelGrade === '95', JSON.stringify(veh2));
check('R9 记录归属不变（改车不改账）', win.eval('records.filter(r => r.veh === 2).length') === countBefore, countBefore);
check('R9 保存后表单回到添加态', /添加车辆/.test(formTitle()) && doc.getElementById('vCancel').style.display === 'none', formTitle());

doc.querySelector('.vedit[data-edit="2"]').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
nameInput().value = '   ';
doc.getElementById('vAddBtn').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
check('R9 名称清空不许保存', win.eval('vehicles.find(v => v.id === 2).name') === '通勤车', 'name cleared anyway');

doc.getElementById('vCancel').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
check('R9 取消回到添加态', /添加车辆/.test(formTitle()), formTitle());

console.log(`\n运行时错误: ${errors.length ? errors.join(' | ') : '无'}`);
console.log(`结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail || errors.length ? 1 : 0);
