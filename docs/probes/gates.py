import subprocess
R='/home/loop-claude/equip-recipe-rollout'
S=[
 ('G21','compose.yaml','"127.0.0.1:8080:8080"','"0.0.0.0:8080:8080"',1),
 ('G22','compose.yaml','"127.0.0.1:3000:3000"','"3000:3000"',1),
 ('G23','compose.yaml','SEED_PASSWORD: ${SEED_PASSWORD:?SEED_PASSWORD를 지정하라}','SEED_PASSWORD: ${SEED_PASSWORD:-demo}',1),
 ('G24','web/Dockerfile','ENV API_ORIGIN=http://api:8080\nRUN','ENV API_ORIGIN=http://api:8080 NEXT_PUBLIC_API_ORIGIN=http://localhost:8080\nRUN',1),
 ('G25','backend/Dockerfile','WORKDIR /app\n','WORKDIR /app\nENV SPRING_DATASOURCE_PASSWORD=rollout\n',1),
 ('G26','web/app/login/page.tsx','\n','\n// 예: operator1\n',1),
 ('G27','compose.yaml','      SERVER_ADDRESS: 0.0.0.0\n      SERVER_PORT: "8081"','      SERVER_PORT: "8081"',1),
 ('G28','compose.yaml','${POSTGRES_PASSWORD:?POSTGRES_PASSWORD를 지정하라}','${POSTGRES_PASSWORD:-rollout}',None),
]
for n,f,old,new,cnt in S:
    p=R+'/'+f; s=open(p).read(); assert old in s,n
    open(p,'w').write(s.replace(old,new,cnt) if cnt else s.replace(old,new))
    r=subprocess.run(['bash','scripts/deploy-gates.sh'],cwd=R,capture_output=True,text=True)
    subprocess.run('git show HEAD:%s > %s'%(f,f),shell=True,cwd=R,check=True)
    fails=[l for l in r.stderr.splitlines() if l.startswith('FAIL')]
    print(n,'exit=',r.returncode,fails)
st=subprocess.run(['git','status','--porcelain','--untracked-files=no'],cwd=R,capture_output=True,text=True).stdout
print('dirty=',repr(st))
