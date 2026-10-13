import json,struct,math
from pathlib import Path
out=Path(__file__).resolve().parents[1] / 'app/src/main/assets';out.mkdir(exist_ok=True)
positions=[];normals=[];indices=[]
faces=[((1,0,0),[(.5,-.5,-.5),(.5,.5,-.5),(.5,.5,.5),(.5,-.5,.5)]),((-1,0,0),[(-.5,-.5,.5),(-.5,.5,.5),(-.5,.5,-.5),(-.5,-.5,-.5)]),((0,1,0),[(-.5,.5,-.5),(-.5,.5,.5),(.5,.5,.5),(.5,.5,-.5)]),((0,-1,0),[(-.5,-.5,.5),(-.5,-.5,-.5),(.5,-.5,-.5),(.5,-.5,.5)]),((0,0,1),[(.5,-.5,.5),(.5,.5,.5),(-.5,.5,.5),(-.5,-.5,.5)]),((0,0,-1),[(-.5,-.5,-.5),(-.5,.5,-.5),(.5,.5,-.5),(.5,-.5,-.5)])]
for normal,points in faces:
 i=len(positions)//3
 for p in points:positions.extend(p);normals.extend(normal)
 indices.extend([i,i+1,i+2,i,i+2,i+3])
binary=struct.pack('<%sf'%len(positions),*positions)+struct.pack('<%sf'%len(normals),*normals)+struct.pack('<%sH'%len(indices),*indices)
materials=[];meshes=[];nodes=[{'name':'solar-house','children':[]}]
colors={'concrete':[.68,.71,.7,1],'charcoal':[.08,.10,.11,1],'metal':[.4,.46,.5,1],'glass':[.13,.24,.29,1],'solar':[.04,.14,.27,1],'mint':[.13,.85,.53,1],'blue':[.12,.48,.95,1],'warm':[.98,.66,.25,1]}
for name,color in colors.items():
 mat={'name':name,'pbrMetallicRoughness':{'baseColorFactor':color,'metallicFactor':.65 if name in ('metal','solar') else 0,'roughnessFactor':.4 if name=='solar' else .7}}
 if name in ('mint','blue','warm'):mat['emissiveFactor']=[c*.5 for c in color[:3]]
 materials.append(mat);meshes.append({'primitives':[{'attributes':{'POSITION':0,'NORMAL':1},'indices':2,'material':len(materials)-1}]})
def box(name,size,at,material):
 nodes.append({'name':name,'mesh':list(colors).index(material),'scale':size,'translation':at});nodes[0]['children'].append(len(nodes)-1)
box('base',[4,.12,3.2],[0,-.08,0],'charcoal');box('ground',[3.85,.04,3.05],[0,0,0],'metal')
box('lower-house',[2.25,.84,1.7],[.25,.47,-.15],'concrete');box('lower-roof',[2.48,.1,1.92],[.25,.92,-.15],'metal')
box('upper-house',[1.4,.86,1.56],[.64,1.4,-.2],'concrete');box('upper-roof',[1.62,.08,1.78],[.64,1.86,-.2],'charcoal')
box('terrace-post',[.1,.74,.1],[-.78,1.36,.52],'concrete');box('terrace-roof',[.7,.08,1.75],[-.52,1.82,-.14],'concrete')
for x in [-.85,-.21]:box('railing',[.025,.27,1.45],[x,1.17,-.12],'metal')
box('front-glass',[1.22,.53,.025],[.61,.5,.735],'glass');box('upper-glass',[1.04,.53,.025],[.64,1.41,.62],'glass')
for row in range(2):
 for col in range(3):
  x=.24+col*.32;z=-.66+row*.48
  box('panel-frame',[.3,.025,.44],[x,1.916,z],'metal');box('panel-cell',[.27,.015,.4],[x,1.94,z],'solar')
box('battery-case',[.45,.68,.38],[-1.45,.4,.8],'charcoal');box('battery-fill',[.35,.45,.025],[-1.45,.39,1.005],'mint')
box('inverter',[.48,.58,.18],[-.9,.36,.85],'metal');box('inverter-led',[.33,.03,.025],[-.9,.38,.955],'mint')
box('grid-pole',[.045,1.6,.045],[1.55,.8,-1.04],'metal');box('grid-crossbar',[.42,.04,.04],[1.55,1.53,-1.04],'metal')
box('window-light',[.96,.32,.01],[.64,1.4,.641],'warm')
routes=[([.7,1.96,-.35],[-.9,.45,.95],'mint'),([-1.45,.5,1.05],[-.9,.45,.95],'mint'),([-.9,.45,.95],[.6,.65,.77],'mint'),([1.55,1.5,-1.04],[.6,.65,.77],'blue')]
for i,(start,end,mat) in enumerate(routes):
 for n in range(10):
  t=n/9;p=[a+(b-a)*t for a,b in zip(start,end)];box(f'cable_{i}_{n}',[.025,.025,.025],p,mat)
 for n in range(6):box(f'flow_{i}_{n}',[.035,.035,.035],start,mat)
g={'asset':{'version':'2.0','generator':'Inverter Link Android procedural house'},'scene':0,'scenes':[{'nodes':[0]}],'nodes':nodes,'meshes':meshes,'materials':materials,'buffers':[{'byteLength':len(binary)}],'bufferViews':[{'buffer':0,'byteOffset':0,'byteLength':len(positions)*4,'target':34962},{'buffer':0,'byteOffset':len(positions)*4,'byteLength':len(normals)*4,'target':34962},{'buffer':0,'byteOffset':(len(positions)+len(normals))*4,'byteLength':len(indices)*2,'target':34963}],'accessors':[{'bufferView':0,'componentType':5126,'count':24,'type':'VEC3','min':[-.5,-.5,-.5],'max':[.5,.5,.5]},{'bufferView':1,'componentType':5126,'count':24,'type':'VEC3'},{'bufferView':2,'componentType':5123,'count':36,'type':'SCALAR'}]}
j=json.dumps(g,separators=(',',':')).encode();j+=b' '*((-len(j))%4);binary+=b'\0'*((-len(binary))%4)
data=struct.pack('<4sII',b'glTF',2,12+8+len(j)+8+len(binary))+struct.pack('<I4s',len(j),b'JSON')+j+struct.pack('<I4s',len(binary),b'BIN\0')+binary
(out/'solar-house.glb').write_bytes(data)
print(len(nodes),'nodes;',len(data),'bytes')
