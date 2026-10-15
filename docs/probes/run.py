import subprocess,sys,json
R='/home/loop-claude/equip-recipe-rollout'
K='backend/src/main/kotlin/com/rollout/'
SEEDS={
 'N1':(K+'api/Audit.kt','runCatching { UriUtils.decode(request.requestURI, StandardCharsets.UTF_8) }.getOrDefault(request.requestURI)','request.requestURI','com.rollout.AuditInvariantTest.percentEncodedPathIsAuditedAsTheSameOperation'),
 'N2':(K+'api/Audit.kt','.param("action", action.take(COLUMN_MAX))','.param("action", action)','com.rollout.AuditInvariantTest.longUnauthenticatedPathIsStillAudited'),
 'N3':(K+'api/RecipeController.kt',', created_by = :actor where id',' where id','com.rollout.AuthorizationMatrixTest.editorOfOthersDraftCannotApproveIt'),
 'N4':(K+'job/TaskOutcomeService.kt','			task.lastError == JobService.CANCEL_NOTE -> "CANCELLED"\n','','com.rollout.TaskOutcomeTest.claimedTaskOfCancelledJobIsNotRetried'),
 'N5':(K+'job/TaskOutcomeService.kt',"where t.id = :id and t.status = 'CLAIMED' and t.claimed_by = :worker","where t.id = :id and t.status = 'CLAIMED' and :worker = :worker",'com.rollout.TaskOutcomeTest.staleOwnerCannotFinishOrExtendReclaimedTask'),
 'N5b':(K+'job/LeaseHeartbeat.kt',"and claimed_by = :worker","and :worker = :worker",'com.rollout.TaskOutcomeTest.staleOwnerCannotFinishOrExtendReclaimedTask'),
 'N6':(K+'job/TaskOutcomeService.kt','		audit(taskId, task, "OK", mapOf("outcome" to "SUCCEEDED", "history" to result.name))\n','','com.rollout.TaskOutcomeTest.workerOutcomesWriteSystemAuditRows'),
 'N8':(K+'api/AdminControllers.kt','			requireText(username, 50)\n			requireText(displayName, 100)\n			require(password.isNotEmpty())\n','','com.rollout.EquipmentApiTest.contractConstraintsAreEnforcedAsInvalidRequest'),
 'N9':(K+'api/AdminControllers.kt','if (body.consoleUrl != currentUrl && body.consolePassword == null)','if (false)','com.rollout.EquipmentApiTest.contractConstraintsAreEnforcedAsInvalidRequest'),
 'N10':(K+'api/RecipeController.kt','			require(params.keys.all(PARAM_KEY::matches))\n','','com.rollout.EquipmentApiTest.contractConstraintsAreEnforcedAsInvalidRequest'),
 'N11':(K+'api/AdminControllers.kt','	"lastApplied" to rs.getString("last_applied"),','	"consoleUser" to "cu",\n	"lastApplied" to rs.getString("last_applied"),','com.rollout.EquipmentApiTest.consoleCredentialsNeverAppearInResponses'),
 'N12':(K+'api/DemoSeed.kt','				on conflict (code) do nothing\n','','com.rollout.SeedTest.demoSeedIsIdempotentAndAbsentWithoutFlag'),
 'N7':(K+'worker/ClaimLoop.kt','val owner = "$workerId-${claimSeq.incrementAndGet()}"','val owner = workerId','com.rollout.e2e.RolloutE2ETest.allTwelveEquipmentsAppliedWithTwelveHistoryRows'),
}
for name in sys.argv[1:]:
    f,old,new,test=SEEDS[name]
    p=R+'/'+f; s=open(p).read(); assert s.count(old)>=1,(name,'anchor missing')
    open(p,'w').write(s.replace(old,new))
    r=subprocess.run(['scripts/test.sh','--tests',test],cwd=R,capture_output=True,text=True)
    fails=[l for l in (r.stdout+r.stderr).splitlines() if 'FAILED' in l and '>' in l][:3]
    subprocess.run('git show HEAD:%s > %s'%(f,f),shell=True,cwd=R,check=True)
    st=subprocess.run(['git','status','--porcelain','--untracked-files=no'],cwd=R,capture_output=True,text=True).stdout
    print(name,'exit=',r.returncode,fails,'dirty=',repr(st),flush=True)
