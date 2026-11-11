-- 파일 이름 순서로 뒤에 오는 zulu 를 부른다 — 로더가 모듈 이름을 앞에서 선언해야 돈다.
alpha = {}

function alpha.wrap(x)
  return 'a(' .. zulu.wrap(x) .. ')'
end
