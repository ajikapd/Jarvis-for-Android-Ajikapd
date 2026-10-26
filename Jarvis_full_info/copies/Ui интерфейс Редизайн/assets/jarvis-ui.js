document.addEventListener('click',e=>{
 const t=e.target.closest('[data-toggle]'); if(t){t.classList.toggle('on'); return;}
 const chip=e.target.closest('[data-chip-group] .chip'); if(chip){chip.parentElement.querySelectorAll('.chip').forEach(x=>x.classList.remove('active'));chip.classList.add('active');}
 const pulse=e.target.closest('[data-pulse]'); if(pulse){pulse.classList.toggle('active');}
 const copy=e.target.closest('[data-copy]'); if(copy){const txt=copy.getAttribute('data-copy'); navigator.clipboard?.writeText(txt); copy.textContent='Скопировано'; setTimeout(()=>copy.textContent='Копировать',900);}
});
