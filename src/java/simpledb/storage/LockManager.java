/**
 * Manages locks.
 *
 * All the field read/write operations are protected by this
 * @Threadsafe
 */
class LockManager {

    final int LOCK_WAIT = 10;       // ms
    final Random random = new Random();

    // maps TransactionId to a Set of Pages
    final Map<TransactionId,Set<PageId>> _tid2pages;

    // set of pages that are locked
    final Map<PageId,Set<TransactionId>> _page2tids;

    // permission under which page got locked
    final Map<PageId,Permissions> _page2perm;

    final Map<TransactionId, List<TransactionId>> _waitsFor;

    private LockManager() {
        _tid2pages = new HashMap<>();
        _page2tids = new HashMap<>();
        _page2perm = new HashMap<>();
        _waitsFor = new HashMap<>();

    }


    //methods to check waits for graph
    public synchronized boolean checkWaitsFor(TransactionId origin, List<TransactionId> goal) {

        List<TransactionId> start = _waitsFor.get(origin);
        if (start == null) return false;
        for (TransactionId t : start) {
            List<TransactionId> newGoal = new ArrayList<>(goal);
            newGoal.add(t);
            if (goal.contains(t) || checkWaitsFor(t, newGoal)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean checkWaitsForDeadlock(TransactionId tid) {
        return checkWaitsFor(tid, Collections.singletonList(tid));
    }


    /**
     * Tried to acquire a lock on page pid for transaction tid, with
     * permissions perm.
     *
     * @throws DeadlockException after on cycle-based deadlock
     */
    public void acquireLock(TransactionId tid, PageId pid, Permissions perm)
            throws DeadlockException {

        while(!lock(tid, pid, perm)) {

            synchronized(this) {
                List<TransactionId> v = _waitsFor.computeIfAbsent(tid, k -> new ArrayList<>());
                Set<TransactionId> holder = _page2tids.get(pid);
                if(holder != null) {
                    holder = new HashSet<>(holder);
                    holder.remove(tid);
                    v.addAll(holder);
                    _waitsFor.put(tid,v);
                    if (checkWaitsForDeadlock(tid)) {
                        //System.out.println("Cycle based deadlock in " + _waitsFor);
                        _waitsFor.remove(tid);
                        throw new DeadlockException();
                    }
                }
            }

            try {
                Thread.sleep(LOCK_WAIT);
            } catch (InterruptedException ignored) {
            }

        }

        synchronized(this) {
            _waitsFor.remove(tid);
        }

    }


    /**
     * Release all locks corresponding to TransactionId tid.
     */
    public synchronized void releaseAllLocks(TransactionId tid, boolean commit) {
        Set<PageId> s =  _tid2pages.get(tid);
        if(s == null)
            return;

        Set<PageId> sx = new HashSet<>(s);
        for (PageId pid : sx) {

            //System.out.println("RELEASING LOCK ON PAGE " + pid.pageno());
            if (commit) {
                // if we commit, then we flush the page to disk under the FORCE policy
                try {
                    // System.out.println("bp flushing page " + pid.pageno());
                    Page p = pages.get(pid);
                    if (p != null) {
                        //not necessary to write it if its not dirty
                        //p.markDirty(true, tid);
                        //really, pages aught to be have been flushed already before we do this!
                        // No, we flush pages here in early labs with FORCE buffer pool policy.
                        // We get rid of these flushes when we switch to NO FORCE in the recovery lab
                        flushPage(pid);
                        p.setBeforeImage(); // next abort should only roll back to here
                    }
                } catch (IOException e) {
                    throw new RuntimeException("failed during commit: " + e);
                }
            } else if ((_page2perm.get(pid)).equals(Permissions.READ_WRITE)) {
                // if we abort, then we restore the page from disk iff we had a
                //   write lock.
                // System.out.println("bp reloading page " + pid.pageno());
                // better have done rollback first if we are allowing dirty pages to go to disk
                Page p = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
                pages.put(pid, p);
            }

            releaseLock(tid, pid);
        }
    }

    public synchronized Set<PageId> pagesLockedByTid(TransactionId tid) {
        return _tid2pages.get(tid);
    }


    /** Return true if the specified transaction has a lock on the specified page */
    public synchronized boolean holdsLock(TransactionId tid, PageId p) {
        Set<TransactionId> tset =  _page2tids.get(p);
        if (tset == null) return false;
        return tset.contains(tid);
    }

    /**
     * Returns whether or not this tid/pid/perm is already locked by another object.
     * if nobody is holding a lock, then this tid/pid/perm can acquire the lock.
     *
     * if perm == READ
     *  if tid is holding any sort of lock on pid, then the tid can acquire the lock (return false).
     *
     *  if another tid is holding a READ lock on pid, then the tid can acquire the lock (return false).
     *  if another tid is holding a WRITE lock on pid, then tid can not currently
     *  acquire the lock (return true).
     *
     * else
     *   if tid is THE ONLY ONE holding a READ lock on pid, then tid can acquire the lock (return false).
     *   if tid is holding a WRITE lock on pid, then the tid already has the lock (return false).
     *
     *   if another tid is holding any sort of lock on pid, then the tid can not currenty acquire the lock (return true).
     */
    private boolean locked(TransactionId tid, PageId pid, Permissions perm) {
        Set<PageId> pset = null;
        Set<TransactionId> tset = null;
        Permissions p = null;

        synchronized (this){
            pset=_tid2pages.get(tid);
            tset= _page2tids.get(pid);
            p=_page2perm.get(pid);
        }

        // System.out.println("locked(): pid = " + pid);

        // if nobody is holding a lock on this page, duh, grant lock
        if(p == null)
            return false;

        // if perm == READ
        if(perm == Permissions.READ_ONLY) {
            // if tid is holding any sort of lock on pid, duh, grant lock
            if(pset != null && pset.contains(pid))
                return false;

            // if another tid is holding a READ lock on pid, grant lock
            if(p == Permissions.READ_ONLY)
                return false;

            //otherwise, someone else has the lock, so we can't have it
            if(p == Permissions.READ_WRITE)
                return true;

            System.out.println("locked weirdness, 1");


            // if perm == READ_WRITE
        } else {
            // if tid is THE ONLY ONE holding a READ lock on pid, this is allowed but we will need to
            // change lock to WRITE later
            if(p == Permissions.READ_ONLY && tset.contains(tid) && tset.size() == 1)
                return false;

            // if tid is holding a WRITE lock on pid, duh, grant lock
            if(p == Permissions.READ_WRITE && tset.contains(tid))
                return false;

            // if another tid is holding any sort of lock on pid, sleep, goto start
            if(tset.size() != 0)
                return true;

            System.out.println("locked weirdness, 2");
        }
        System.exit(-1);

        System.out.println("locked weirdness, 3");
        System.exit(-1);
        return true;
    }


    public  synchronized void releaseLock(TransactionId tid, PageId pid) {
        //System.out.println("released " + _page2perm.get(pid) + " on " + pid.pageno() + " for " + tid);

        Set<PageId> pset = _tid2pages.get(tid);
        if(pset != null) {
            pset.remove(pid);
            if(pset.isEmpty())
                _tid2pages.remove(tid);
        }

        Set<TransactionId> tset = _page2tids.get(pid);
        if(tset != null) {
            tset.remove(tid);
            if(tset.isEmpty()) {
                _page2tids.remove(pid);
                _page2perm.remove(pid);
            }
        }
    }


    private  synchronized boolean lock(TransactionId tid, PageId pid, Permissions perm) {

        if(locked(tid, pid, perm))
            return false;

        Set<PageId> pset = _tid2pages.computeIfAbsent(tid, k -> new HashSet<>());

        pset.add(pid);
            /*
              System.out.print(tid + " locks: ");
              while (it.hasNext()) {
              PageId p = it.next();

              System.out.print(p.pageno() + ",");
              }
              System.out.println("");
            */

        Set<TransactionId> tset = _page2tids.computeIfAbsent(pid, k -> new HashSet<>());
        // System.out.println("putting " + tid + " -> " + pid.pageno() + " in _page2tids");
        tset.add(tid);

        //System.out.println("putting " + pid + " -> " + perm + " in _page2perm");
        Permissions old = _page2perm.get(pid);
        if (old == null || (old == Permissions.READ_ONLY && perm == Permissions.READ_WRITE)) {
            _page2perm.put(pid, perm);
        }
        return true;
    }
}