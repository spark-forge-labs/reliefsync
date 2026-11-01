import json
m=json.load(open("vo/meta.json"))["dur"]
seg=[]  # (name, kind, dur, [(line, offset_in_seg)])
def add(n,k,d,l=()): seg.append(dict(name=n,kind=k,dur=round(d,3),lines=list(l)))
add("A","new",0.6+m["L1"]+0.4,[("L1",0.6)])
add("B","new",0.7+m["L2"]+0.7,[("L2",0.7)])
add("C1","clip",7.4,[("L3",1.6)])
add("C2","clip",12.5,[("L4",1.8)])
add("C3","clip",14.6/1.5,[("L5",1.4)])
n3=0.8+m["L6"]+0.6+m["L7"]+0.5+m["L8"]+0.5
add("N3","new",n3,[("L6",0.8),("L7",0.8+m["L6"]+0.6),("L8",0.8+m["L6"]+0.6+m["L7"]+0.5)])
add("C4","clip",12/1.25,[("L9",0.6)])
add("C5","clip",6.4,[("L10",0.6)])
add("N4","new",0.7+m["L11"]+1.1,[("L11",0.7)])
add("N5","new",0.8+m["L12"]+1.4,[("L12",0.8)])
t=0
for s in seg: s["start"]=round(t,3); t+=s["dur"]
for s in seg: s["lines"]=[(l,round(s["start"]+o,3)) for l,o in s["lines"]]
json.dump(dict(segs=seg,total=round(t,3)),open("sched.json","w"),indent=1)
for s in seg: print(s["name"],s["start"],s["dur"],s["lines"])
print("total",t)
